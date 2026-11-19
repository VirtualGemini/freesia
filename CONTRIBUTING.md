# Contributing to Freesia

English | [简体中文](./CONTRIBUTING.zh-CN.md)

Read the [commit specification](./docs/specs/COMMIT_SPEC.md) and [PR and maintainer merge workflow](./docs/specs/PR_SPEC.md). Application changes also use `docs/specs/DEVELOPMENT_SPEC.md` and `docs/ARCHITECTURE.md`; these implementation documents enter with the application foundation separately from the workflow bootstrap.

Use one task branch created from current main, named `type/kebab-case`, such as `feat/iam-login`. An issue number may be included when useful, but an issue is optional. Describe the problem, scope, expected behavior and validation in the PR. Implement, commit and address review findings on the task branch, then hand off the PR for a maintainer squash merge. Do not update main directly; agents do not merge PRs.

Backend `cd freesia-admin && mvn test` uses temporary PostgreSQL/Valkey containers; frontend `cd freesia-web && pnpm lint && pnpm build` validates its package. `make check` combines both packages and repository gates. Before publishing or marking ready, also run `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`. Check documentation links, status and language consistency with the relevant specs.

Keep `<type>(<scope>): <subject>` and the existing types, adding DCO `Signed-off-by` and `Docs-Impact` trailers. Frontend installation configures Husky task-branch/metadata/staged checks and a full-range pre-push check; CI repeats them. Respect user limits such as “do not commit” or “do not push” and stop after the authorized preparation.

Use the PR template to record behavior, executed checks, documentation and configuration/API/schema effects. Confirm breaking changes with a maintainer in a GitHub issue or PR discussion before implementation and describe migration and rollback. A human maintainer reviews the change and merges manually after required checks pass. Initial GitHub setup and the sole-maintainer exception are in the PR spec. Record source commits and retain required third-party licenses/copyright; exclude artifacts, personal settings, runtime data and the local directories listed in `.gitignore`.
