## Repository guidance

### New product foundation

Before backend work, read `docs/ARCHITECTURE.md` for the Freesia foundation, module boundaries and capability import stages. Backend lives in `freesia-admin/`; frontend lives in `freesia-web/`. Import selected old capabilities only after the prerequisite foundation; retain the frontend architecture.

### Repository conventions

Read `docs/specs/DEVELOPMENT_SPEC.zh-CN.md` for development rules and `docs/specs/COMMIT_SPEC.zh-CN.md` when preparing commits.

### Pull requests and maintainer merges

Before changing files or preparing commits/PRs, read `docs/specs/PR_SPEC.zh-CN.md`. Work on one task branch per change, describe its scope and validation in the PR, and hand off the reviewed PR to a maintainer for the final merge. Maintainers may use merge commits, squash or rebase. Agents must not commit/push to `main`, merge PRs, or enable auto-merge. User limits such as "do not commit" or "do not push" take precedence; stop at the authorized stage.

### Work tracking

Use GitHub issues when a separate planning or discussion record is useful. A PR can describe a complete task without an issue. Follow the repository's tracked specifications and PR template; local agent tools and working documents are optional and stay outside commits.
