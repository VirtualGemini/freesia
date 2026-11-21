## Repository guidance

### New product foundation

Backend lives in `freesia-admin/`; frontend lives in `freesia-web/`. Follow the applicable foundation and module rules in `specs/`. Import selected old capabilities only after the prerequisite foundation; retain the frontend architecture.

### Repository conventions

Read `specs/COMMIT_SPEC.zh-CN.md` when preparing commits. For application changes, consult `specs/DEVELOPMENT_SPEC.zh-CN.md` when it is present.

### Pull requests and maintainer merges

Before changing files or preparing commits/PRs, read `specs/PR_SPEC.zh-CN.md`. Work on one task branch per change, describe its scope and validation in the PR, and hand off the reviewed PR to a maintainer for the final merge. Maintainers may use merge commits, squash or rebase. Agents must not commit/push to `main`, merge PRs, or enable auto-merge. User limits such as "do not commit" or "do not push" take precedence; stop at the authorized stage.

## Agent skills

### Issue tracker

Issues and specs use local Markdown. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

Use a single-context layout. See `docs/agents/domain.md`.
