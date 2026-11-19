"""Policy regression cases use disposable Git repositories, never the project index/history."""

import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE = Path(__file__).resolve().parents[2] / "scripts/repository_policy.py"
SPEC = importlib.util.spec_from_file_location("repository_policy", MODULE)
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)


def message(subject="feat(iam): add account query", impact="updated", extra=""):
    return (subject + "\n\nSigned-off-by: Policy Test <policy@example.invalid>\n"
            + "Docs-Impact: " + impact + "\n" + extra)


def exemption(reviewer="@reviewer"):
    return message(impact="none", extra="Docs-Impact-Reason: unchanged external contract\n"
                   + "Docs-Impact-Approved-By: " + reviewer + "\n")


class GitCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="freesia-policy-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Policy Test")
        self.git("config", "user.email", "policy@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        self.git("config", "core.hooksPath", str(self.root / "disabled-test-hooks"))

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()

    def write(self, name, content="content\n"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self, text=None):
        self.git("add", ".")
        self.git("commit", "-q", "-m", text or message())
        return self.git("rev-parse", "HEAD")

    def foundation(self, maintainers="@VirtualGemini\n"):
        self.write("scripts/repository_policy.py", "# policy initialization marker\n")
        self.write(".github/maintainers.txt", maintainers)
        self.write(".policy-files", ".docs-impact.yml\n.policy-files\nscripts/**\n.github/**\n")
        self.write(".docs-impact.yml", json.dumps({"rules": [{
            "name": "behavior", "paths": ["src/**"], "documents": ["docs.md"], "require": "all"
        }]}))
        self.write("docs.md", "baseline docs\n")
        return self.commit(message("chore(repo): initialize policy"))


class CommitMessages(GitCase):
    def test_valid_conventional_types_and_real_trailers(self):
        for kind in "feat fix docs style refactor perf test build ci revert chore wip".split():
            with self.subTest(kind=kind):
                self.assertEqual(policy.validate_message(self.root, message(kind + ": describe change"))["impact"], "updated")

    def test_empty_scope_and_nonconventional_subject_are_rejected(self):
        for subject in ("feat(): broken", "fix code", "fixup! fix(iam): query", "feat(iam): trailing."):
            with self.subTest(subject=subject), self.assertRaises(policy.PolicyError):
                policy.validate_message(self.root, message(subject))

    def test_signoff_in_body_is_not_a_footer(self):
        text = message() + "\nA later paragraph turns the earlier metadata into body text.\n"
        with self.assertRaisesRegex(policy.PolicyError, "Signed-off-by"):
            policy.validate_message(self.root, text)

    def test_missing_and_duplicate_metadata_are_rejected(self):
        samples = ["feat(iam): add query\n", message() + "Docs-Impact: none\n",
                   message().replace("policy@example.invalid", "invalid"),
                   exemption().replace("Docs-Impact-Reason: unchanged external contract", "Docs-Impact-Reason:")]
        for text in samples:
            with self.subTest(message=text), self.assertRaises(policy.PolicyError):
                policy.validate_message(self.root, text)

    def test_exemption_needs_reason_and_github_handle(self):
        for extra in ("", "Docs-Impact-Reason: none\n", "Docs-Impact-Reason: none\nDocs-Impact-Approved-By: reviewer\n"):
            with self.subTest(extra=extra), self.assertRaises(policy.PolicyError):
                policy.validate_message(self.root, message(impact="none", extra=extra))
        self.assertEqual(policy.validate_message(self.root, exemption())["impact"], "none")

    def test_breaking_footer_and_documentation_impact_are_required(self):
        with self.assertRaisesRegex(policy.PolicyError, "BREAKING CHANGE"):
            policy.validate_message(self.root, message("feat(iam)!: reshape login"))
        text = message("feat(iam)!: reshape login", extra="BREAKING CHANGE: login contract changes\n")
        self.assertTrue(policy.validate_message(self.root, text)["breaking"])
        with self.assertRaisesRegex(policy.PolicyError, "cannot use"):
            policy.validate_message(self.root, text.replace("Docs-Impact: updated", "Docs-Impact: none")
                                    + "Docs-Impact-Reason: none\nDocs-Impact-Approved-By: @reviewer\n")

    def test_wip_is_local_only(self):
        with self.assertRaisesRegex(policy.PolicyError, "wip"):
            policy.validate_message(self.root, message("wip(iam): query draft"), ready=True)


class PathAccounting(unittest.TestCase):
    def test_rename_accounts_both_paths(self):
        self.assertEqual(policy.change_sets("R100\0old/docs.md\0new/docs.md\0"),
                         ({"old/docs.md", "new/docs.md"}, {"old/docs.md", "new/docs.md"}))

    def test_deleted_document_cannot_satisfy_update(self):
        self.assertEqual(policy.change_sets("D\0docs.md\0M\0src/code.java\0"),
                         ({"docs.md", "src/code.java"}, {"src/code.java"}))

    def test_copy_changes_only_destination_and_preserves_unusual_names(self):
        self.assertEqual(policy.change_sets("C100\0src/a\0src/b\0M\0docs/a\tb.md\0"),
                         ({"src/b", "docs/a\tb.md"}, {"src/b", "docs/a\tb.md"}))


class HistoryChecks(GitCase):
    def test_source_change_requires_companion_docs(self):
        base = self.foundation()
        self.write("src/account.java")
        head = self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "documentation rule"):
            policy.check_range(self.root, base, head)

    def test_source_and_docs_in_one_commit_pass(self):
        base = self.foundation()
        self.write("src/account.java")
        self.write("docs.md", "updated query contract\n")
        head = self.commit()
        self.assertEqual(len(policy.check_range(self.root, base, head)[3]), 1)

    def test_later_valid_commit_does_not_excuse_earlier_invalid_metadata(self):
        base = self.foundation()
        self.write("misc.txt")
        self.commit("fix(iam): missing metadata")
        self.write("another.txt")
        head = self.commit(message("docs(repo): record query"))
        with self.assertRaisesRegex(policy.PolicyError, "Signed-off-by"):
            policy.check_range(self.root, base, head)

    def test_head_cannot_weaken_matrix_to_judge_itself(self):
        base = self.foundation()
        self.write(".docs-impact.yml", '{"rules": []}\n')
        self.write("src/account.java")
        head = self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "documentation rule"):
            policy.check_range(self.root, base, head)

    def test_policy_files_cannot_claim_exemption(self):
        base = self.foundation()
        self.write(".docs-impact.yml", '{"rules": []}\n')
        head = self.commit(exemption())
        with self.assertRaisesRegex(policy.PolicyError, "policy changes"):
            policy.check_range(self.root, base, head)

    def test_prior_pr_commit_cannot_remove_policy_path_exemption_guard(self):
        base = self.foundation()
        self.write(".policy-files", ".policy-files\n")
        self.commit(message("chore(repo): edit executor registry"))
        self.write("scripts/gate.py", "# still protected by trusted base\n")
        head = self.commit(exemption())
        with self.assertRaisesRegex(policy.PolicyError, "policy changes cannot use"):
            policy.check_range(self.root, base, head)

    def test_initialized_target_cannot_silently_lose_its_matrix(self):
        self.foundation()
        (self.root / ".docs-impact.yml").unlink()
        base = self.commit(message("chore(repo): remove matrix"))
        self.write("src/account.java")
        head = self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "no documentation matrix"):
            policy.check_range(self.root, base, head)

    def test_deleted_docs_do_not_satisfy_companion_requirement(self):
        base = self.foundation()
        (self.root / "docs.md").unlink()
        self.write("src/account.java")
        head = self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "documentation rule"):
            policy.check_range(self.root, base, head)

    def test_bootstrap_never_executes_head_policy_or_calls_github(self):
        self.write("README.md", "before policy\n")
        base = self.commit("chore: initialize")
        self.write("scripts/repository_policy.py", "raise Exception('untrusted head')\n")
        head = self.commit()
        with patch.object(policy, "get_api", side_effect=AssertionError("must not call GitHub")):
            policy.check_pr(self.root, base, head, "VirtualGemini/freesia", "1")

    def test_document_exemption_passes_local_checks_but_needs_remote_review(self):
        base = self.foundation()
        self.write("src/account.java")
        head = self.commit(exemption())
        self.assertEqual(policy.check_range(self.root, base, head)[3][head]["impact"], "none")

    def test_breaking_commit_with_updated_docs_passes_without_local_approval_file(self):
        base = self.foundation()
        self.write("src/account.java")
        self.write("docs.md", "breaking contract\n")
        head = self.commit(message("feat(iam)!: reshape account",
                                   extra="BREAKING CHANGE: migrate account consumers\n"))
        self.assertTrue(policy.check_range(self.root, base, head)[3][head]["breaking"])

    def test_breaking_commit_still_requires_updated_contract_docs(self):
        base = self.foundation()
        self.write("src/account.java")
        head = self.commit(message("feat(iam)!: reshape account",
                                   extra="BREAKING CHANGE: migrate account consumers\n"))
        with self.assertRaisesRegex(policy.PolicyError, "documentation rule"):
            policy.check_range(self.root, base, head)


class MergeMethods(GitCase):
    def topic_change(self, valid=True):
        base = self.foundation()
        self.git("switch", "-q", "-c", "feat/account-query")
        self.write("src/account.java", "query\n")
        self.write("docs.md", "query contract\n")
        content = self.commit(message() if valid else "feat(iam): missing metadata")
        self.git("switch", "-q", "main")
        return base, content

    def test_default_merge_message_passes_and_source_metadata_is_checked(self):
        base, content = self.topic_change()
        self.git("merge", "--no-ff", "--no-edit", "feat/account-query")
        head = self.git("rev-parse", "HEAD")
        self.assertEqual(set(policy.check_main_update(self.root, base, head)), {content})

    def test_merge_wrapper_cannot_hide_invalid_source_commit(self):
        base, _ = self.topic_change(valid=False)
        self.git("merge", "--no-ff", "--no-edit", "feat/account-query")
        with self.assertRaisesRegex(policy.PolicyError, "Signed-off-by"):
            policy.check_main_update(self.root, base, "HEAD")

    def test_extra_edits_in_a_merge_need_their_own_metadata(self):
        base, content = self.topic_change()
        self.git("merge", "--no-ff", "--no-commit", "feat/account-query")
        self.write("src/additional.java", "extra edit\n")
        self.commit("Merge branch 'feat/account-query'")
        with self.assertRaisesRegex(policy.PolicyError, "subject"):
            policy.check_main_update(self.root, base, "HEAD")
        self.git("commit", "--amend", "-q", "-m", message("fix(iam): include additional query"))
        head = self.git("rev-parse", "HEAD")
        self.assertEqual(set(policy.check_main_update(self.root, base, head)), {content, head})

    def test_conflict_resolution_is_checked_as_a_content_commit(self):
        base = self.foundation()
        self.git("switch", "-q", "-c", "feat/account-query")
        self.write("docs.md", "topic contract\n")
        content = self.commit(message("docs(iam): describe topic query"))
        self.git("switch", "-q", "main")
        self.write("docs.md", "main contract\n")
        before = self.commit(message("docs(iam): describe existing query"))
        self.assertEqual(policy.run_git(self.root, "merge", "--no-ff", "--no-commit",
                                        "feat/account-query", allow_failure=True).returncode, 1)
        self.write("docs.md", "combined contract\n")
        head = self.commit(message("docs(iam): reconcile query contracts"))
        self.assertEqual(set(policy.check_main_update(self.root, before, head)), {content, head})

    def test_squash_commit_keeps_conventional_metadata(self):
        base, _ = self.topic_change()
        self.git("merge", "--squash", "feat/account-query")
        head = self.commit()
        self.assertEqual(set(policy.check_main_update(self.root, base, head)), {head})

    def test_rebased_commits_are_validated_after_fast_forward(self):
        _, _ = self.topic_change()
        self.write("other.txt", "main advances\n")
        before = self.commit(message("chore(repo): record unrelated work"))
        self.git("switch", "-q", "feat/account-query")
        self.git("rebase", "main")
        content = self.git("rev-parse", "HEAD")
        self.git("switch", "-q", "main")
        self.git("merge", "--ff-only", "feat/account-query")
        self.assertEqual(set(policy.check_main_update(self.root, before, "HEAD")), {content})

    def test_initial_main_update_checks_the_root_commit(self):
        self.write("README.md", "foundation\n")
        head = self.commit(message("chore(repo): initialize project"))
        self.assertEqual(set(policy.check_main_update(self.root, "0" * 40, head)), {head})

    def test_rewritten_main_history_is_rejected(self):
        base = self.foundation()
        self.write("other.txt", "later work\n")
        before = self.commit(message("chore(repo): record work"))
        with self.assertRaisesRegex(policy.PolicyError, "preserve the existing history"):
            policy.check_main_update(self.root, before, base)


class ApprovalChecks(GitCase):
    def setUp(self):
        super().setUp()
        self.base = self.foundation()
        self.write("misc.txt")
        self.head = self.commit()

    def pr(self, author="contributor", author_type="User"):
        return {"base": {"sha": self.base}, "head": {"sha": self.head},
                "user": {"login": author, "type": author_type},
                "_commit_authors": {self.head: author}}

    def review(self, user="VirtualGemini", state="APPROVED", head=None, user_type="User", identifier=1):
        return {"id": identifier, "user": {"login": user, "type": user_type},
                "state": state, "commit_id": head or self.head}

    def check(self, pr, reviews, metadata=None, changed=None):
        policy.trusted_reviews(self.root, self.base, self.head, pr, reviews,
                               metadata or {}, changed or set())

    def test_policy_change_needs_trusted_current_head_human_approval(self):
        self.check(self.pr(), [self.review()], changed={"scripts/repository_policy.py"})
        for reviews in ([], [self.review(head=self.base)], [self.review(user_type="Bot")]):
            with self.subTest(reviews=reviews), self.assertRaisesRegex(policy.PolicyError, "trusted maintainer"):
                self.check(self.pr(), reviews, changed={"scripts/repository_policy.py"})

    def test_sole_maintainer_exception_uses_authenticated_pr_author(self):
        self.check(self.pr(author="VirtualGemini"), [], changed={"scripts/repository_policy.py"})
        with self.assertRaises(policy.PolicyError):
            self.check(self.pr(author="VirtualGemini", author_type="Bot"), [], changed={"scripts/repository_policy.py"})

    def test_multiple_maintainers_cannot_approve_their_own_policy_change(self):
        self.write(".github/maintainers.txt", "@VirtualGemini\n@reviewer\n")
        self.base = self.commit(message("chore(repo): add maintainer"))
        self.head = self.base
        with self.assertRaisesRegex(policy.PolicyError, "trusted maintainer"):
            self.check(self.pr(author="VirtualGemini"), [self.review()], changed={"scripts/repository_policy.py"})
        self.check(self.pr(author="VirtualGemini"), [self.review(user="reviewer")], changed={"scripts/repository_policy.py"})

    def test_docs_exemption_rejects_pr_author_commit_author_and_stale_review(self):
        for named in ("contributor", "writer", "reviewer"):
            pr = self.pr()
            pr["_commit_authors"][self.head] = "writer"
            info = policy.validate_message(self.root, exemption("@" + named))
            reviews = [self.review(user=named, head=self.base if named == "reviewer" else self.head)]
            with self.subTest(named=named), self.assertRaisesRegex(policy.PolicyError, "non-author"):
                self.check(pr, reviews, {self.head: info})
        info = policy.validate_message(self.root, exemption("@reviewer"))
        self.check(self.pr(), [self.review(user="reviewer")], {self.head: info})

    def test_sole_maintainer_can_confirm_docs_exemption_without_fake_self_review(self):
        info = policy.validate_message(self.root, exemption("@VirtualGemini"))
        self.check(self.pr(author="VirtualGemini"), [], {self.head: info})

    def test_comment_does_not_withdraw_approval_but_dismissal_and_changes_do(self):
        first = self.review()
        for state, approved in (("COMMENTED", True), ("DISMISSED", False), ("CHANGES_REQUESTED", False)):
            latest = self.review(state=state, identifier=2)
            self.assertEqual(bool(policy.current_approvals([first, latest], self.head)), approved)

    def test_metadata_sha_must_match_checked_current_head(self):
        pr = self.pr()
        pr["head"]["sha"] = self.base
        with self.assertRaisesRegex(policy.PolicyError, "does not match"):
            self.check(pr, [])


class LocalGuards(GitCase):
    def test_main_and_invalid_branch_cannot_commit(self):
        self.foundation()
        with self.assertRaisesRegex(policy.PolicyError, "main"):
            policy.guard_branch(self.root)
        self.git("switch", "-q", "-c", "feat/account-query")
        policy.guard_branch(self.root)
        self.git("switch", "-q", "-c", "feature")
        with self.assertRaisesRegex(policy.PolicyError, "type/kebab-case"):
            policy.guard_branch(self.root)

    def test_main_push_and_main_deletion_are_rejected(self):
        head = self.foundation()
        for local in (head, "0" * 40):
            row = "refs/heads/main " + local + " refs/heads/main " + head + "\n"
            with patch("sys.stdin", io.StringIO(row)), self.assertRaisesRegex(policy.PolicyError, "maintainer"):
                policy.guard_push(self.root, "origin")

    def test_topic_push_validates_complete_range(self):
        base = self.foundation()
        self.git("update-ref", "refs/remotes/origin/main", base)
        self.git("switch", "-q", "-c", "feat/account-query")
        self.write("src/account.java")
        self.write("docs.md", "query contract\n")
        head = self.commit()
        row = "refs/heads/feat/account-query " + head + " refs/heads/feat/account-query " + "0" * 40 + "\n"
        with patch("sys.stdin", io.StringIO(row)):
            policy.guard_push(self.root, "origin")


class PullRequestShape(unittest.TestCase):
    def shape(self):
        return {"title": "feat(iam): add account query", "body": "Describe account query behavior and checks.",
                "base": {"ref": "main"}, "head": {"ref": "feat/account-query"}}

    def test_task_branch_and_description_pass_without_issue(self):
        policy.validate_current_pr(self.shape())

    def test_bad_pr_title_and_target_are_rejected(self):
        for field, value in (("title", "fix things"), ("title", "wip(iam): draft"),
                             ("base", {"ref": "release"}), ("head", {"ref": "feature"})):
            pr = self.shape()
            pr[field] = value
            with self.subTest(field=field), self.assertRaises(policy.PolicyError):
                policy.validate_current_pr(pr)

    def test_issue_number_is_optional_and_not_limited_to_two_digits(self):
        for branch in ("feat/account-query", "feat/01-account-query", "feat/123-account-query"):
            pr = self.shape()
            pr["head"]["ref"] = branch
            with self.subTest(branch=branch):
                policy.validate_current_pr(pr)

    def test_issue_link_does_not_become_a_local_file_requirement(self):
        pr = self.shape()
        pr["body"] += "\nCloses #123"
        policy.validate_current_pr(pr)


class PackageBootstrap(GitCase):
    def test_local_hooks_work_before_frontend_package_is_committed(self):
        self.foundation()
        self.write("scripts/repository_policy.py", MODULE.read_text())
        self.git("switch", "-q", "-c", "chore/01-policy-bootstrap")
        message_file = self.root / "commit-message.txt"
        message_file.write_text(message("chore(repo): initialize workflow"))
        hooks = MODULE.parent.parent / "freesia-web/.husky"
        for name, args in (("pre-commit", []), ("commit-msg", [str(message_file)])):
            result = subprocess.run(["sh", str(hooks / name), *args], cwd=self.root,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_uncommitted_package_is_not_mistaken_for_committed_ci_source(self):
        base = self.foundation()
        self.write("freesia-admin/pom.xml", "<project/>\n")
        self.assertFalse(policy.package_presence(self.root, "freesia-admin/pom.xml", base))
        self.commit()
        self.assertTrue(policy.package_presence(self.root, "freesia-admin/pom.xml", base))

    def test_existing_backend_cannot_be_deleted_to_skip_ci(self):
        self.foundation()
        self.write("freesia-admin/pom.xml", "<project/>\n")
        base = self.commit()
        (self.root / "freesia-admin/pom.xml").unlink()
        self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "cannot be removed to skip CI"):
            policy.package_presence(self.root, "freesia-admin/pom.xml", base)

    def test_existing_frontend_cannot_be_deleted_to_skip_ci(self):
        self.foundation()
        self.write("freesia-web/package.json", "{}\n")
        base = self.commit()
        (self.root / "freesia-web/package.json").unlink()
        self.commit()
        with self.assertRaisesRegex(policy.PolicyError, "cannot be removed to skip CI"):
            policy.package_presence(self.root, "freesia-web/package.json", base)


if __name__ == "__main__":
    unittest.main()
