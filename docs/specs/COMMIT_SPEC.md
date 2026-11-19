# Freesia Admin Commit Spec

English | [简体中文](./COMMIT_SPEC.zh-CN.md)

This repository uses Conventional Commits.

## 1. Format

```text
<type>(<scope>): <subject>
```

`scope` is optional, but recommended.

Examples:

```text
feat(frontend): add file config status filter
fix(backend): handle expired reset password code
docs(root): add contribution guide
refactor(file): split file config query service
chore(repo): update gitignore rules
```

## 2. Allowed Types

- `feat`: new feature.
- `fix`: bug fix.
- `docs`: documentation change.
- `style`: code style change without behavior impact.
- `refactor`: refactoring without feature or bug-fix intent.
- `perf`: performance improvement.
- `test`: test addition or update.
- `build`: build flow, dependency, or packaging configuration change.
- `ci`: CI configuration or script change.
- `revert`: revert a commit.
- `chore`: tooling, maintenance, or non-business change.
- `wip`: temporary work-in-progress commit. Clean up or squash before merge.

## 3. Recommended Scopes

- `root`: root README, contribution guide, or repository configuration.
- `docs`: documentation.
- `frontend`: frontend application.
- `backend`: backend application.
- `auth`: authentication, registration, password recovery.
- `user`: users and profile.
- `role`: roles and permission assignment.
- `menu`: menus and dynamic routes.
- `file`: file center.
- `file-config`: file configuration management.
- `dependencies`: backend BOM and version management.
- `common`: response, pagination and error contracts.
- `framework`: backend starter capabilities.
- `iam`: accounts, authentication, RBAC and sign-in policy module.
- `notification`: notification channels, mail and templates module.
- `audit`: audit records and queries module.
- `dashboard`: product overview module.
- `extensions`: optional extension adapters.
- `infra`: root PostgreSQL/Valkey and other infrastructure orchestration.
- `server`: backend startup module and application configuration.
- `repo`: repository governance, ignore rules, hooks, or commit rules.

Custom scopes are allowed when they describe the changed area more clearly.

## 4. Subject Rules

- Keep the subject short and specific.
- Start with a lowercase verb when writing in English. Chinese subjects are also allowed.
- Do not end with a period.
- Describe what changed instead of writing generic text such as "update code".

Recommended:

```text
fix(auth): reject expired reset password code
docs(root): add monorepo development guide
```

Not recommended:

```text
fix: bug
docs: update
chore: misc changes
```

## 5. Body and Footer

Add a body when the commit:

- Affects multiple packages.
- Has important design tradeoffs.
- Changes compatibility, configuration, database schema, or API contracts.
- Fixes an issue whose cause is not clear from the subject.

Issue references may be placed in the footer:

```text
Closes #123
```

Breaking changes must use `BREAKING CHANGE:` and should include migration notes in the pull request.

## 6. Tooling

The root commit spec describes the repository-wide message format. Tooling that depends on a single package's toolchain should stay under that package.

The [PR workflow](./PR_SPEC.md) uses task-branch commits and maintainer merges with merge commits, squash or rebase; no direct main commits. Keep the format and types, adding real trailers to every content commit:

```text
Signed-off-by: Your Name <your-email@example.com>
Docs-Impact: updated
```

Use `git commit -s` to sign off contributions you are entitled to submit. For no documentation impact:

```text
Docs-Impact: none
Docs-Impact-Reason: concrete explanation
Docs-Impact-Approved-By: @reviewer-login
```

Range checks verify metadata and actual GitHub exemption approval; naming a reviewer is not approval. Breaking commits include `BREAKING CHANGE:` and `Docs-Impact: updated`. Confirm the change with a maintainer in a GitHub issue or PR discussion before implementation, and link the confirmation and migration/rollback plan in the PR. See the PR spec. Local `wip` is temporary; ready ranges cannot include wip, `fixup!` or `squash!`.

Before publishing a task branch or marking a PR ready, run `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`. A later fix/revert does not excuse invalid earlier metadata in the range.

## 7. Pre-PR Checklist

- The commit message follows this spec.
- Each commit has one main intent.
- No unrelated formatting, build output, or local configuration is included.
- Frontend changes have run the relevant frontend checks.
- Backend changes have run the relevant backend checks.
