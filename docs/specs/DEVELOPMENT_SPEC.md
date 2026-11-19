# Freesia Development Specification

English | [简体中文](./DEVELOPMENT_SPEC.zh-CN.md)

These are the conventions for the new Freesia product, replacing the previous admin development specification. Build the foundation before importing selected capabilities; the previous repository is a reference, not the product structure.

## 1. Product and repository

The product is Freesia and the administration app is Freesia Admin. `freesia-admin/` is the Java 25 / Spring Boot 4.1 Maven reactor: dependency BOM, framework foundation, server application and optional extensions. `freesia-web/` retains Vue 3, TypeScript, Vite, Element Plus and Pinia. `infra/` owns local PostgreSQL/Valkey orchestration; Flyway owns business schema. `docs/` holds tracked conventions and architecture; task scope and validation belong in the PR or related GitHub issue. Package-specific tooling stays in its package.

## 2. Backend organization

The application root in `freesia-server` is `com.freesia.admin` and contains only the application class. Direct child packages are the five business modules: `iam`, `file`, `notification`, `audit`, `dashboard`. Sign-in policy, verification rules and TOTP belong to IAM. Module roots contain only `package-info.java`; public contracts/DTOs/events live in `api/` declared with `@NamedInterface("api")`, and implementation lives under `internal/<capability>/`. Each module owns its tables and SQL.

Framework packages are `com.freesia.common` / `com.freesia.framework.*`. They provide technical configuration without business tables/SQL or references to `com.freesia.admin..`. The three starters have no production dependencies on each other; within this project they depend only on common, while tests may compose starters. Extensions provide connections/resource limits; business QueryPorts and SQL stay in the owning server module.

Name use cases explicitly, for example `ChangeRolePermissions` and `RoleQueries`, and keep related requests, rules and persistence close. Controllers belong to their capability. Avoid global controller/service/impl/mapper folders, unnecessary interfaces/Impl pairs, and artificial domain objects for simple CRUD.

Cross-module access uses named APIs. Declare allowed dependencies in package-info, for example `"iam :: api"`, when an actual call exists. Do not access another module's internal entities, mappers or tables. Notification callers supply the recipient language; notification does not query accounts. Modulith verification and the framework-to-business dependency check are required.

## 3. Reads, writes and transactions

JdbcClient performs lists, details, aggregates and command validation queries, mapping to explicit DTOs. MyBatis-Flex performs writes. Both share one DataSource and Spring JDBC transaction manager. Freesia shares one FlexDataSource bean, disables Flex's separate transaction auto-configuration and installs SpringManagedTransactionFactory to ensure this shared transaction.

Use transactions at explicit write-use-case entry points. Avoid private Flex transactions, dynamic Flex multi-source routing and cross-database transactions. Read-only replica routing will follow audit queries, remains disabled by default and may only operate at the connection-pool layer. Authorization, sensitive validation and reads after writes always use the primary database. Bind SQL parameters; whitelist dynamic identifiers/sorting. Raw queries explicitly filter state and soft deletion. Errors use clear exceptions and consistent HTTP/business status; never report success after swallowing an exception. External mail/storage calls require failure compensation and must not hold long database transactions.

## 4. Database

Maintain PostgreSQL only, defaulting to 18; validate 17 when required. Versioned migrations live under `freesia-admin/freesia-server/src/main/resources/db/migration/` as one linear history. Never edit an applied migration. Small account/role/menu/configuration tables remain ordinary tables. Use BIGINT identity/sequence IDs. DTO IDs use boxed `Long` and always serialize as strings; primitive `long` / `int` counts stay numeric. Use database `timestamptz` and Java `Instant`, serialized as ISO-8601 UTC; clients choose the display zone, and mail/exports receive an explicit zone. Database uniqueness/not-null/required foreign keys enforce invariants.

Pin Flyway history with `spring.flyway.default-schema=public`. Business SQL explicitly qualifies the `freesia` schema, so creating a schema with the account's name cannot change the history location through `search_path` and cause repeated migrations.

Partition audit tables only with actual time queries and retention requirements. Unique keys include the partition key; define precreation, late records and sealing. Choose partial/GIN/BRIN indexes from actual SQL and query plans. BM25 requires an optional extension. Large logs use cursor/time boundaries; ordinary admin pages can use numbered pagination.

## 5. Authentication and RBAC

No tenants. The target is account → role → menu/button permission with historical R_SUPER/R_ADMIN/R_USER codes. Role level limits administration, not implicit permission or inheritance. Keep one permission-code catalog shared by annotations, seed menus, buttons and routes.

Enforce authorization in the backend. Test disabled roles/accounts, revocation, privilege escalation and the last active super administrator. Authorization defaults to database reads. The current starter obtains permissions from `AuthorizationSource` and denies permission checks without an implementation. The future IAM `none|versioned` cache defaults to `none`; verify version checks, concurrent invalidation and stale refills with acceptance tests, not just TTL or broadcasts. Authorization-management writes always check the database directly. The latest frontend restoration keeps the historical RBAC login flow, management routes, menus and business pages in full. Backend capability integration proceeds separately; unimplemented APIs are not a reason to delete pages or substitute a single product shell. Do not add mock login, mock permissions or a default administrator token.

## 6. Frontend

Retain api/views/components/hooks/router/store/utils/types/locales/config responsibilities. Product identity is centralized in `freesia-web/src/config/product.ts`; hide unset links and synchronize static HTML title/favicon. Use Freesia component/style prefixes and isolated Freesia Admin storage keys. Responses retain `code/msg/data`; pagination is `Api.Common.PaginatedResponse` / `Api.Common.CursorResponse`.

The latest user instruction restores the full frontend from `9dd4f30a`, before the permission-policy refactor. Keep themes, styles, components, hooks, directives, utilities, authentication/accounts/roles/menus and other pages without pruning based on current usage. Historical folders and APIs remain until the backend capabilities are integrated and aligned with the five modules. Distinguish restored source from working business integration. Retain build/lint/format/commit tools; exclude unused scripts and personal settings.

HTTP errors preserve status, business code, server `msg` and Trace ID. Existing session/menu flows stay intact; no mock login or default token is added. Original static routes, management menus and dynamic registration remain intact; document API integration separately. Preserve the original LICENSE and include `LICENSE.txt` in distribution.

Keep TypeScript strict; use unknown plus validation at uncertain boundaries and avoid new any. Retain formatter/lint conventions, typed props/emits, and loading/error/empty states. Frontend permissions govern presentation; backend decisions remain authoritative.

## 7. Dependencies and optional facilities

`freesia-dependencies` imports Boot/Modulith/Sa-Token BOMs and locks remaining versions. The reactor parent inherits this BOM and manages plugins, `release=25` and `-parameters`. Never import the previous Boot BOM, Boot 3 or MyBatis-Plus. Commit the frontend lockfile and install frozen. Caffeine is bounded local caching; Valkey holds sessions/shared state; they do not automatically form a two-level cache.

DuckDB/ClickHouse versions are locked only in the BOM; they are absent from default dependencies, pools and jobs. Add them only for real business queries. DuckDB requires an optional dependency, explicit profile, conditional configuration and limits on threads/memory/temp storage; disabled adapters have no external startup dependency. Do not accept arbitrary user SQL. Verify archive files/manifests/time boundaries before deleting online data; define synchronization and freshness separately for real-time analytics.

## 8. Importing capabilities

After foundation checks pass, extract one complete capability slice at a time. Record source commit, frontend/backend contract, dependencies and acceptance criteria. Implement within the new modules rather than copying whole old framework/system/service packages. Preserve all legacy file, mail and TOTP capabilities. Each capability PR lists source behavior, configuration, endpoints, data, extension points and errors with acceptance evidence, and corrects legacy defects.

Check product names, logos, mail subjects/templates, OpenAPI, logs, cache keys, Java packages, database/container/volume names and documentation links. Remove old branding from product code while retaining third-party licenses/copyright. Do not import contributor lists or old Git history.

## 9. Validation and collaboration

Develop through task branches and PRs with manual maintainer squash merges, per [PR_SPEC.md](./PR_SPEC.md). Agents implement, validate and prepare authorized PRs for handoff; they do not commit/push main, merge or auto-merge. Current user limits such as “do not commit” or “do not push” take precedence.

Run `mvn test` in `freesia-admin/` for backend changes; SQL/transaction tests use real PostgreSQL. Module changes include structure checks and module integration tests where useful. Package with `mvn package` and start `freesia-server/target/freesia-admin.jar`; development commands are in the root README. Run `pnpm lint` and `pnpm build` in `freesia-web/`; UI changes need layout/interaction checks. Do not add implementation-mirroring tests for simple reversible edits.

Tests cover meaningful risks: rollback, authorization, pagination, partition pruning and module collaboration. Exclude build artifacts, dependencies, logs, credentials and temporary reference archives. Maintain root documentation in both languages and distinguish implemented behavior, targets and unverified assumptions. Retain the existing [Conventional Commits specification](./COMMIT_SPEC.md).
