# Freesia branches, pull requests and maintainer merges

English | [简体中文](./PR_SPEC.zh-CN.md)

Code, documentation, dependencies and configuration in Freesia's Java/Vue monorepo follow **task branch → PR → human review → maintainer merge**. Maintainers may use merge commits, squash or rebase. Keep the [commit format](./COMMIT_SPEC.md). Public workflow and development specifications live under `specs/`; application implementation rules enter with the application foundation.

## 1. Branches and execution scope

`main` is the only long-lived branch, stays buildable and is updated by maintainers through PRs. Use one branch per task, created from current main and named `type/kebab-case`, such as `feat/iam-login`. An issue number may be included, such as `feat/123-iam-login`, but an issue is optional. Describe the task and validation in the PR; delete the branch after merge.

Developers and agents implement, validate and address review findings on task branches. **Agents do not merge PRs, push main, enable auto-merge or bypass rules.** The maintainer listed in `.github/maintainers.txt` performs the final merge. User scope takes precedence: “do not commit,” “do not push” or “prepare only” stops at reviewed files and validation, without commits or publication. Preserve existing work when changing branches.

For a workspace ready to switch branches:

```sh
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c feat/iam-login
```

## 2. Implementation and commits

Read the task requirements and tracked development/architecture specifications. Describe expected behavior and documentation impact in the PR. New runtime dependencies need an explanation of purpose, license, alternatives and removal cost in an issue or PR discussion, confirmed by a maintainer; upgrades use separate PRs.

Validate the affected package: backend `mvn test` uses temporary PostgreSQL/Valkey; frontend `pnpm lint` / `pnpm build` includes typechecking. Process/documentation changes run `make check-policy` and link checks. Stage only task files, inspect the staged diff and `git diff --cached --check`, and use `git commit -s` to sign off contributions you are entitled to submit. Never forge another person's signoff.

The repository ignores `docs/`, `.scratch/` and `.agents/`; keep them local. `docs/` contains optional skill working material; `specs/` remains tracked. Published instructions, documentation checks and CI use tracked files. Preserve required license and copyright notices with the distributed files; do not rely on ignored directories for them.

After committing, check the new commit. Before publishing, opening a PR or marking it ready, validate all reachable commits:

```sh
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
```

Source checks do not replace history checks. A later valid commit or revert does not remove an invalid earlier commit. Repair unpublished history; coordinate published rewrites and protect unknown remote work. Local `wip` is temporary and cannot appear in a ready range; `fixup!` and `squash!` must be cleaned up.

Keep Husky in `freesia-web`: pre-commit guards main and runs lint-staged; commit-msg validates metadata and staged documentation; pre-push guards main and validates the range. CI repeats the gates so local hooks are not the sole control.

## 3. PR contents and review

When publication is authorized, push only the task branch and target main; use Draft while incomplete. Titles follow Conventional Commits. The [PR template](../.github/pull_request_template.md) records the problem, final behavior, scope, commands/results, API/schema/configuration/license effects, external mail/storage calls, documentation and migration/rollback. Link a GitHub issue when there is a separate discussion or requirement record.

Human reviewers check expected behavior, development rules, module boundaries, transactions/errors, tests, dependencies, commits and documentation. Describe behavioral evidence in the PR as well as build results. Authors and agents cannot approve their own changes; agent findings assist human review. Critical/high findings block merge; medium findings are fixed or accepted with a written maintainer rationale.

## 4. Documentation and breaking changes

Every content commit includes DCO `Signed-off-by` and `Docs-Impact` trailers; syntax is in the commit spec. `.docs-impact.yml` maps changes to documents. Staged checks use committed HEAD rules, individual commits use parent rules, and PRs use trusted base rules. A change cannot relax its own rules. Renames count old/new paths; deleted documents are not update evidence.

`Docs-Impact: none` needs a reason and named reviewer. Trusted policy verifies actual current-head GitHub approval from someone other than the PR/commit author. If trusted base lists exactly one maintainer and that person authored the PR, they may confirm their own documentation exemption or policy change. This narrow exception **does not authorize an agent merge or replace GitHub merge requirements**.

Breaking published API/schema/configuration/frontend behavior requires maintainer confirmation in a GitHub issue or PR discussion before implementation. Link the confirmation in the PR and describe migration and rollback. The maintainer verifies this discussion during review; local checks validate commit metadata and documentation, while trusted policy validates current-head reviews.

The commit includes `BREAKING CHANGE:` and `Docs-Impact: updated`. A maintainer defines the scope of unpublished internal interface changes in the discussion rather than treating every refactor as a published-contract break.

## 5. CI and trusted policy

`ci.yml` runs backend, frontend and repository checks on PRs and main. It does not publish or receive mail/storage/release credentials; tests use temporary infrastructure.

Main updates validate all newly added content commits, so merge commits, squash and rebase are supported. A clean merge wrapper needs no separate message metadata when its tree matches Git's automatic merge; its source commits are checked. Merge resolutions or additional edits remain content changes and use the commit specification.

Workflow policy can be committed separately before the application packages. Those jobs explicitly report packages not yet in Git history; once an entrypoint exists on the target branch, removing it to skip CI fails. Application packages enter through separate PRs rather than being bundled with policy setup.

`Trusted Policy / policy` executes only trusted base policy, reads the PR head as Git data, and checks commits, documentation, approvals and policy changes. It never installs PR dependencies or executes PR code under `pull_request_target`, as described in [GitHub's event guidance](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request_target).

`.policy-files` identifies gate executors; CODEOWNERS protects IAM, security, transactions/schema, builds, hooks, workflows and collaboration rules. These changes need maintainer review, and approvals for older revisions cannot authorize a new head.

After approval, the maintainer reruns the current PR's Trusted Policy in Actions; the script fetches current head/reviews again. This trusted workflow does not execute PR-version workflows on `pull_request_review`; new commits/body edits automatically rerun via `pull_request_target`.

## 6. Maintainer merge and completion

The maintainer checks expected behavior, validation evidence, review findings, documentation and all gates, then performs the final merge using merge commits, squash or rebase. Content commits retain Conventional Commit titles, DCO, `Docs-Impact` and applicable breaking trailers. Main CI stays green; delete the task branch and close any related issue.

Report implementation, PR creation, review and merge separately. An agent hands off the reviewable PR and stops before merging; integration is complete after the maintainer merges and main checks pass.

## 7. Initial GitHub configuration (not applied)

This task prepares files without changing remote settings. After reviewing and merging the initialization PR, maintainers configure Rulesets/General:

- Require PRs for main; block deletion and force pushes. Allow merge commits, squash and rebase; disable auto-merge.
- Require at least one human approval, CODEOWNER review, dismissal of stale approvals and resolved discussions.
- Require `CI / Backend`, `CI / Frontend`, `CI / Repository`, `Trusted Policy / policy`, using the names shown by the first real run; require up-to-date base checks.
- Give merge rights only to maintainers. Other contributors use branches/forks; agents and dependency bots receive no merge or ruleset-bypass rights.
- GitHub rejects self-approval, per [the review documentation](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/approving-a-pull-request-with-required-reviews). Maintainer-authored PRs need another eligible human; any administrator-operated exception records its reason and actor. Agents never perform it.

Before base policy exists, the trusted check reports initialization and never falls back to executing PR-supplied policy. The maintainer reviews that one bootstrap PR and local evidence; subsequent PRs execute main's trusted policy. Files/workflows cannot activate remote rulesets themselves; see [GitHub Rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets).
