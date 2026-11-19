#!/usr/bin/env python3
"""Freesia PR policy. Uses the standard library and reads PR source only as Git data."""

import argparse
import fnmatch
import json
import re
import subprocess
import sys
from pathlib import Path


TYPES = "feat|fix|docs|style|refactor|perf|test|build|ci|revert|chore|wip"
SUBJECT = re.compile(r"^(" + TYPES + r")(?:\([a-z0-9][a-z0-9._/-]*\))?(!)?: \S.*$")
HANDLE = re.compile(r"^@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$")
POLICY_MARKER = "scripts/repository_policy.py"


class PolicyError(Exception):
    pass


def run_git(root, *args, input_text=None, allow_failure=False):
    result = subprocess.run(
        ["git", "-C", str(root), *args], input=input_text, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode and not allow_failure:
        raise PolicyError("git " + args[0] + " failed: " + result.stderr.strip())
    return result


def git(root, *args):
    return run_git(root, *args).stdout


def resolve_ref(root, value):
    return git(root, "rev-parse", "--verify", "--end-of-options", value + "^{commit}").strip()


def read_at(root, ref, path):
    if ref is None:
        return None
    result = run_git(root, "show", ref + ":" + path, allow_failure=True)
    return result.stdout if result.returncode == 0 else None


def policy_exists(root, ref):
    return read_at(root, ref, POLICY_MARKER) is not None


def patterns(text):
    return [line.strip().lstrip("/") for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def matches(path, pattern):
    return fnmatch.fnmatchcase(path, pattern.lstrip("/"))


def matches_any(path, choices):
    return any(matches(path, pattern) for pattern in choices)


def trailers(root, message):
    # Only the real footer parsed by Git counts; body text cannot impersonate metadata.
    parsed = run_git(root, "interpret-trailers", "--parse", input_text=message).stdout
    result = {}
    for line in parsed.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            result.setdefault(key.strip().lower(), []).append(value.strip())
    return result


def one_footer(values, key, required=False):
    found = values.get(key.lower(), [])
    if len(found) > 1:
        raise PolicyError("duplicate " + key + " trailer")
    if not found:
        if required:
            raise PolicyError("missing " + key + " trailer")
        return ""
    if required and not found[0]:
        raise PolicyError("empty " + key + " trailer")
    return found[0]


def breaking_footer(message, values):
    regular = one_footer(values, "BREAKING-CHANGE")
    final_block = message.rstrip().split("\n\n")[-1]
    spaced = re.findall(r"^BREAKING CHANGE: (.+)$", final_block, re.MULTILINE)
    if len(spaced) > 1 or (regular and spaced):
        raise PolicyError("duplicate breaking-change footer")
    return regular or (spaced[0] if spaced else "")


def validate_message(root, message, ready=False):
    lines = message.splitlines()
    subject = lines[0] if lines else ""
    found = SUBJECT.fullmatch(subject)
    if not found or subject.endswith("."):
        raise PolicyError("subject must use Conventional Commits without empty scope or trailing period")
    if ready and found.group(1) == "wip":
        raise PolicyError("wip commits must be cleaned up before publishing a ready range")
    values = trailers(root, message)
    signoffs = values.get("signed-off-by", [])
    if not signoffs or any(not re.fullmatch(r"\S[^<>\n]* <[^<>\s]+@[^<>\s]+>", s) for s in signoffs):
        raise PolicyError("a valid DCO Signed-off-by trailer is required")
    impact = one_footer(values, "Docs-Impact", required=True)
    if impact not in ("updated", "none"):
        raise PolicyError("Docs-Impact must be updated or none")
    if impact == "none":
        one_footer(values, "Docs-Impact-Reason", required=True)
        reviewer = one_footer(values, "Docs-Impact-Approved-By", required=True)
        if not HANDLE.fullmatch(reviewer):
            raise PolicyError("Docs-Impact-Approved-By must be a GitHub handle")
    description = breaking_footer(message, values)
    is_breaking = bool(found.group(2) or description)
    if is_breaking:
        if not description:
            raise PolicyError("breaking changes require a BREAKING CHANGE footer")
        if impact != "updated":
            raise PolicyError("breaking changes cannot use Docs-Impact: none")
    return {"impact": impact, "breaking": is_breaking, "footers": values}


def change_sets(raw):
    """NUL-delimited Git paths; renames count both names, deletions cannot satisfy docs."""
    entries = raw.split("\0")
    changed, documents = set(), set()
    index = 0
    while index < len(entries) and entries[index]:
        status = entries[index]
        index += 1
        count = 2 if status[0] in "RC" else 1
        names = entries[index:index + count]
        if len(names) != count:
            raise PolicyError("invalid Git name-status output")
        index += count
        if status[0] == "C":
            names = names[-1:]
        changed.update(names)
        if status[0] != "D":
            documents.update(names)
    return changed, documents


def changes(root, base, head, staged=False):
    if staged:
        raw = git(root, "diff", "--cached", "--name-status", "-z", "--find-renames", "--diff-filter=ACMRD")
    elif base is None:
        raw = git(root, "diff-tree", "--root", "-r", "--no-commit-id", "--name-status", "-z",
                  "--find-renames", "--diff-filter=ACMRD", head)
    else:
        raw = git(root, "diff", "--name-status", "-z", "--find-renames", "--diff-filter=ACMRD", base, head)
    return change_sets(raw)


def trusted_rules(root, ref):
    raw = read_at(root, ref, ".docs-impact.yml")
    if raw is None:
        if policy_exists(root, ref):
            raise PolicyError("initialized trusted policy has no documentation matrix")
        return []
    try:
        rules = json.loads(raw)["rules"]
        validate_rules(rules)
        return rules
    except (ValueError, KeyError, TypeError) as error:
        raise PolicyError("invalid trusted documentation matrix: " + str(error)) from error


def validate_rules(rules):
    if not isinstance(rules, list):
        raise PolicyError("documentation rules must be a list")
    for rule in rules:
        if not isinstance(rule, dict) or rule.get("require") not in ("all", "any"):
            raise PolicyError("documentation rule requires all or any")
        if not isinstance(rule.get("name"), str) or not rule["name"]:
            raise PolicyError("documentation rule requires a name")
        for key in ("paths", "documents"):
            items = rule.get(key)
            if not isinstance(items, list) or not items or any(not isinstance(p, str) or not p for p in items):
                raise PolicyError("documentation rule has invalid " + key)


def trusted_maintainers(root, ref):
    text = read_at(root, ref, ".github/maintainers.txt")
    if text is None:
        raise PolicyError("trusted base has no maintainer list")
    result = {line.lower() for line in patterns(text)}
    if not result or any(not HANDLE.fullmatch(handle) for handle in result):
        raise PolicyError("invalid trusted maintainer list")
    return result


def trusted_policy_paths(root, ref):
    text = read_at(root, ref, ".policy-files")
    if not text and policy_exists(root, ref):
        raise PolicyError("initialized trusted policy has no executor registry")
    return patterns(text) if text else []


def validate_docs(root, ref, changed, documents, metadata):
    if metadata["impact"] == "none":
        if any(matches_any(path, trusted_policy_paths(root, ref)) for path in changed):
            raise PolicyError("repository policy changes cannot use Docs-Impact: none")
        return
    for rule in trusted_rules(root, ref):
        if not any(matches_any(path, rule["paths"]) for path in changed):
            continue
        satisfied = [any(matches(path, pattern) for path in documents) for pattern in rule["documents"]]
        if not (all(satisfied) if rule["require"] == "all" else any(satisfied)):
            raise PolicyError("documentation rule '" + rule["name"] + "' requires updated documents: "
                              + ", ".join(rule["documents"]))


def is_clean_merge(root, commit, parents):
    if len(parents) != 2:
        return False
    merged = run_git(root, "merge-tree", "--write-tree", *parents, allow_failure=True)
    tree = merged.stdout.splitlines()
    return (merged.returncode == 0 and bool(tree)
            and tree[0] == git(root, "rev-parse", commit + "^{tree}").strip())


def check_commit(root, base, commit):
    parents = git(root, "show", "-s", "--format=%P", commit).split()
    # Clean merge wrappers introduce no edits of their own; their source commits are checked separately.
    if is_clean_merge(root, commit, parents):
        return None, set()
    metadata = validate_message(root, git(root, "show", "-s", "--format=%B", commit), ready=True)
    parent = parents[0] if parents else None
    changed, documents = changes(root, parent, commit)
    if metadata["impact"] == "none" and any(
            matches_any(path, trusted_policy_paths(root, base)) for path in changed):
        raise PolicyError("repository policy changes cannot use Docs-Impact: none")
    validate_docs(root, parent, changed, documents, metadata)
    return metadata, changed


def check_range(root, base_value, head_value):
    base, head = resolve_ref(root, base_value), resolve_ref(root, head_value)
    merge_base = git(root, "merge-base", base, head).strip()
    commits = git(root, "rev-list", "--reverse", merge_base + ".." + head).splitlines()
    aggregate_changed, aggregate_docs = changes(root, merge_base, head)
    trigger_files, metadata_by_commit = set(), {}
    for commit in commits:
        metadata, changed = check_commit(root, base, commit)
        if metadata is None:
            continue
        metadata_by_commit[commit] = metadata
        if metadata["impact"] != "none":
            trigger_files.update(changed)
    validate_docs(root, base, trigger_files, aggregate_docs, {"impact": "updated"})
    return base, head, aggregate_changed, metadata_by_commit


def check_main_update(root, before_value, head_value):
    head = resolve_ref(root, head_value)
    if before_value == "0" * 40:
        items = {}
        for commit in git(root, "rev-list", "--reverse", head).splitlines():
            metadata, _ = check_commit(root, None, commit)
            if metadata is not None:
                items[commit] = metadata
    else:
        before = resolve_ref(root, before_value)
        if run_git(root, "merge-base", "--is-ancestor", before, head, allow_failure=True).returncode:
            raise PolicyError("main updates must preserve the existing history")
        _, _, _, items = check_range(root, before, head)
    print("Validated " + str(len(items)) + " content commits added to main")
    return items


def current_approvals(reviews, head):
    """Ignore comments, which do not withdraw an existing formal approval."""
    state = {}
    for review in sorted(reviews, key=lambda item: item.get("id", 0)):
        user = review.get("user", {}).get("login", "").lower()
        if review.get("state") in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
            state[user] = review
    return {user: item for user, item in state.items()
            if item.get("state") == "APPROVED" and item.get("commit_id") == head
            and item.get("user", {}).get("type") == "User"}


def trusted_reviews(root, base, head, pr, reviews, metadata_by_commit, changed):
    if pr.get("base", {}).get("sha") != base or pr.get("head", {}).get("sha") != head:
        raise PolicyError("PR metadata does not match the checked base/head")
    author = pr.get("user", {}).get("login", "").lower()
    if not author:
        raise PolicyError("missing authenticated PR author")
    maintainers = trusted_maintainers(root, base)
    sole_author = (len(maintainers) == 1 and "@" + author in maintainers
                   and pr.get("user", {}).get("type") == "User")
    approvals = current_approvals(reviews, head)
    author_commits = pr.get("_commit_authors", {})
    for commit, metadata in metadata_by_commit.items():
        if metadata["impact"] != "none":
            continue
        named = one_footer(metadata["footers"], "Docs-Impact-Approved-By", required=True).lstrip("@").lower()
        if sole_author and named == author:
            continue
        commit_author = author_commits.get(commit, "").lower()
        if not commit_author:
            raise PolicyError("cannot verify GitHub author for documentation exemption " + commit)
        if named in (author, commit_author) or named not in approvals:
            raise PolicyError("documentation exemption requires a non-author human approval on current head: @" + named)
    policy_changed = any(matches_any(path, trusted_policy_paths(root, base)) for path in changed)
    if policy_changed and not sole_author:
        eligible = {"@" + login for login in approvals if login != author}
        if not (eligible & maintainers):
            raise PolicyError("policy changes need a trusted maintainer's current-head approval")


def validate_current_pr(pr):
    title = pr.get("title", "")
    match = SUBJECT.fullmatch(title)
    if not match or title.endswith(".") or match.group(1) == "wip":
        raise PolicyError("PR title must be a ready Conventional Commit subject")
    if pr.get("base", {}).get("ref") != "main":
        raise PolicyError("PR target must be main")
    branch = pr.get("head", {}).get("ref", "")
    if not valid_branch(branch):
        raise PolicyError("PR branch must use type/kebab-case")


def get_api(repository, endpoint):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise PolicyError("invalid GitHub repository")
    result = subprocess.run(
        ["gh", "api", "--paginate", "--slurp", "repos/" + repository + endpoint],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise PolicyError("cannot read GitHub metadata: " + result.stderr.strip())
    pages = json.loads(result.stdout)
    if not pages:
        raise PolicyError("empty GitHub metadata response")
    return [item for page in pages for item in page] if isinstance(pages[0], list) else pages[0]


def check_pr(root, base_value, head_value, repository, number):
    if not re.fullmatch(r"[1-9][0-9]*", number):
        raise PolicyError("invalid PR number")
    base, head = resolve_ref(root, base_value), resolve_ref(root, head_value)
    if not policy_exists(root, base):
        print("Policy initialization: base predates Freesia policy. Maintainer bootstrap review required.")
        return
    base, head, changed, metadata = check_range(root, base, head)
    pr = get_api(repository, "/pulls/" + number)
    validate_current_pr(pr)
    reviews = get_api(repository, "/pulls/" + number + "/reviews")
    pr["_commit_authors"] = {}
    for commit, info in metadata.items():
        if info["impact"] == "none":
            data = get_api(repository, "/commits/" + commit)
            pr["_commit_authors"][commit] = (data.get("author") or {}).get("login", "")
    trusted_reviews(root, base, head, pr, reviews, metadata, changed)
    print("Trusted PR policy passed for " + head)


def check_worktree(root):
    required = [
        ".github/pull_request_template.md", ".github/CODEOWNERS", ".github/maintainers.txt",
        ".docs-impact.yml", ".policy-files", "Makefile", "scripts/repository_policy.py",
        "specs/PR_SPEC.md", "specs/PR_SPEC.zh-CN.md",
        "specs/COMMIT_SPEC.md", "specs/COMMIT_SPEC.zh-CN.md",
        "freesia-web/.husky/pre-commit", "freesia-web/.husky/commit-msg", "freesia-web/.husky/pre-push",
    ]
    for name in required:
        if not (root / name).is_file():
            raise PolicyError("missing workflow file: " + name)
    validate_rules(json.loads((root / ".docs-impact.yml").read_text())["rules"])
    owners = (root / ".github/CODEOWNERS").read_text()
    owner_rules = [line.split() for line in owners.splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
    maintainers = patterns((root / ".github/maintainers.txt").read_text())
    if not maintainers or any(not HANDLE.fullmatch(item) for item in maintainers):
        raise PolicyError("invalid worktree maintainer list")
    for item in patterns((root / ".policy-files").read_text()):
        matching = [rule for rule in owner_rules if matches(item, rule[0])]
        if not matching or not ({name.lower() for name in matching[-1][1:]}
                                & {name.lower() for name in maintainers}):
            raise PolicyError("policy entry lacks CODEOWNER protection: " + item)
    print("Repository workflow files and policy ownership are consistent")


def valid_branch(branch):
    return bool(re.fullmatch(r"(?:" + TYPES.replace("|wip", "")
                             + r")/[a-z0-9]+(?:-[a-z0-9]+)*", branch))


def package_presence(root, path, base_value):
    if path not in ("freesia-admin/pom.xml", "freesia-web/package.json"):
        raise PolicyError("unknown package entrypoint")
    head = resolve_ref(root, "HEAD")
    if read_at(root, head, path) is not None:
        return True
    if base_value and base_value != "0" * 40:
        base = resolve_ref(root, base_value)
        if read_at(root, base, path) is not None:
            raise PolicyError("existing package cannot be removed to skip CI: " + path)
    print("Policy-only bootstrap: " + path + " has not been committed yet.", file=sys.stderr)
    return False


def guard_branch(root):
    branch = git(root, "symbolic-ref", "--quiet", "--short", "HEAD").strip()
    if branch == "main":
        raise PolicyError("main is maintainer-merged; create a task branch before committing")
    if not valid_branch(branch):
        raise PolicyError("task branch must use type/kebab-case")


def guard_push(root, remote):
    rows = [row.split() for row in sys.stdin.read().splitlines() if row.strip()]
    for row in rows:
        if len(row) != 4:
            raise PolicyError("invalid pre-push ref input")
        local_ref, local_sha, remote_ref, _ = row
        if remote_ref == "refs/heads/main":
            raise PolicyError("main must be updated by a maintainer merging a PR")
        if local_sha == "0" * 40:
            continue
        if not local_ref.startswith("refs/heads/"):
            raise PolicyError("release tag publication is a separate maintainer action")
        branch = local_ref.removeprefix("refs/heads/")
        if not valid_branch(branch):
            raise PolicyError("only task branches may be pushed for review")
        check_range(root, remote + "/main", local_sha)
    print("Topic-branch push and full commit range checks passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-check")
    commands.add_parser("branch")
    package = commands.add_parser("package-presence")
    package.add_argument("path")
    package.add_argument("base")
    message = commands.add_parser("message")
    message.add_argument("file")
    staged = commands.add_parser("staged-message")
    staged.add_argument("file")
    history = commands.add_parser("commits")
    history.add_argument("base")
    history.add_argument("head")
    update = commands.add_parser("main-update")
    update.add_argument("before")
    update.add_argument("head")
    pr = commands.add_parser("pr")
    pr.add_argument("base")
    pr.add_argument("head")
    pr.add_argument("--repository", required=True)
    pr.add_argument("--number", required=True)
    push = commands.add_parser("push")
    push.add_argument("remote")
    args = parser.parse_args()
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
    try:
        if args.command == "self-check":
            check_worktree(root)
        elif args.command == "branch":
            guard_branch(root)
        elif args.command == "package-presence":
            print("present=" + ("true" if package_presence(root, args.path, args.base) else "false"))
        elif args.command in ("message", "staged-message"):
            message_text = Path(args.file).read_text()
            metadata = validate_message(root, message_text)
            if args.command == "staged-message":
                ref = resolve_ref(root, "HEAD")
                changed, documents = changes(root, None, None, staged=True)
                validate_docs(root, ref, changed, documents, metadata)
            print("Commit message and applicable staged policy passed")
        elif args.command == "commits":
            _, _, _, items = check_range(root, args.base, args.head)
            print("Validated " + str(len(items)) + " commits and aggregate documentation impact")
        elif args.command == "main-update":
            check_main_update(root, args.before, args.head)
        elif args.command == "pr":
            check_pr(root, args.base, args.head, args.repository, args.number)
        elif args.command == "push":
            guard_push(root, args.remote)
    except (PolicyError, OSError, ValueError) as error:
        print("error: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
