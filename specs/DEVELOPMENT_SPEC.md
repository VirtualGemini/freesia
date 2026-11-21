# Freesia Development Specification

English | [简体中文](./DEVELOPMENT_SPEC.zh-CN.md)

These are the development conventions for Freesia. Build the shared foundation before implementing business capabilities.

## 1. Product and repository

The product is Freesia and the administration app is Freesia Admin. `freesia-admin/` is the Java 25 / Spring Boot 4.1 Maven reactor: dependency BOM, framework foundation, server application and optional extensions. `freesia-web/` uses Vue 3, TypeScript, Vite, Element Plus and Pinia. `infra/` owns local PostgreSQL/Valkey orchestration; Flyway owns business schema. `specs/` holds tracked conventions and architecture; task scope and validation belong in the PR or related GitHub issue. `docs/` contains ignored local skill working material. Package-specific tooling stays in its package.

## 2. Backend organization

The application root in `freesia-server` is `com.freesia.admin` and contains only the application class. Direct child packages are the five business modules: `iam`, `file`, `notification`, `audit`, `dashboard`. Sign-in policy, verification rules and TOTP belong to IAM. Module roots contain only `package-info.java`; public contracts/DTOs/events live in `api/` declared with `@NamedInterface("api")`, and implementation lives under `internal/<capability>/`. Each module owns its tables and SQL.

Framework packages are `com.freesia.common` / `com.freesia.framework.*`. They provide technical configuration without business tables/SQL or references to `com.freesia.admin..`. The three starters have no production dependencies on each other; within this project they depend only on common, while tests may compose starters. Extensions provide connections/resource limits; business QueryPorts and SQL stay in the owning server module.

Name use cases explicitly, for example `ChangeRolePermissions` and `RoleQueries`, and keep related requests, rules and persistence close. Controllers belong to their capability. Avoid global controller/service/impl/mapper folders, unnecessary interfaces/Impl pairs, and artificial domain objects for simple CRUD.

Cross-module access uses named APIs. Declare allowed dependencies in package-info, for example `"iam :: api"`, when an actual call exists. Do not access another module's internal entities, mappers or tables. Notification callers supply the recipient language; notification does not query accounts. Modulith verification and the framework-to-business dependency check are required.

## 3. Reads, writes and transactions

JdbcClient performs lists, details, aggregates and command validation queries, mapping to explicit DTOs. MyBatis-Flex performs writes. Both share one DataSource and Spring JDBC transaction manager. Freesia shares one FlexDataSource bean, disables Flex's separate transaction auto-configuration and installs SpringManagedTransactionFactory to ensure this shared transaction. The data starter registers a native UUID type handler for generated-key retrieval and UUID parameter binding; database-generated entity keys use `@Id(keyType = KeyType.Auto)`.

Use transactions at explicit write-use-case entry points. Avoid private Flex transactions, dynamic Flex multi-source routing and cross-database transactions. Read-only replica routing will follow audit queries, remains disabled by default and may only operate at the connection-pool layer. Authorization, sensitive validation and reads after writes always use the primary database. Bind SQL parameters; whitelist dynamic identifiers/sorting. Raw queries explicitly filter state and soft deletion. Errors use clear exceptions and consistent HTTP/business status; never report success after swallowing an exception. External mail/storage calls require failure compensation and must not hold long database transactions.

Resolve error messages from the supported `Accept-Language`, defaulting to Simplified Chinese. Set `spring.messages.fallback-to-system-locale=false` so missing application translations use the default bundle or error text instead of the host language.

### 3.1 Backend internationalization

- Use Spring `MessageSource` and UTF-8 `.properties` resources, not another translation framework, database translation center, or translation administration UI. Fixed system text is reviewed and released with code. Business data such as nicknames, custom role names, and notes remains verbatim.
- The external languages are `zh` (Simplified Chinese) and `en`. HTTP negotiates supported languages by `Accept-Language` preferences and weights. Chinese regional tags such as `zh-CN` and `zh-TW` select the existing Simplified Chinese resources; `en-US` and other English tags select English. This does not claim additional translations. Missing or unsupported preferences default to `zh`; do not infer language from URL `lang`, cookies, host settings, or IP.
- Use Spring's request locale context, without another ThreadLocal, static Spring container, or static `MessageUtils`. Mail, exports, and background work receive an explicit language/`Locale`, not a leftover request context. Locale never changes ordinary API UTC timestamps, numeric types, or field names.
- `ErrorCode` explicitly defines its numeric identity, stable translation key, HTTP status, and English diagnostic fallback. Keys use `<module>.error.<name>`, for example `iam.error.invalid-credentials`; do not derive them from Java class/enum names or use Chinese text as identity. Common remains JDK-only: contracts and resources, without Spring dependencies.
- Business code raises an error identity plus arguments; the HTTP response exit localizes `msg`. `ApiException.getMessage()` remains locale-independent diagnostic text. Preserve `code/msg/data`, success code 200, and the existing `OK`. The frontend displays server error messages without translating backend keys again. Logs/audits use stable codes, actions, and necessary redacted parameters rather than viewer-language-dependent semantics.
- Common owns `i18n/common/messages.properties` (the Simplified Chinese default) and `messages_en.properties`. Shared application resources live under server `i18n/messages*`; each business module owns `i18n/<module>/messages*`. Business keys use the `iam/file/notification/audit/dashboard` namespaces rather than accumulating in common. Other categories are `.validation.`, `.message.`, and `.export.`.
- Register actual resource paths explicitly through `spring.messages.basename`, using a list or comma-separated values. Each basename needs a default file with a complete English counterpart. The starter appends common resources after application basenames and exposes one injectable MessageSource. Application basename order defines precedence; business modules must not duplicate keys.
- Use `spring.messages.encoding=UTF-8`, no host-locale fallback, and no raw-key responses (`fallback-to-system-locale=false`, `use-code-as-default-message=false`; the default configuration enforces both). Resolve resources in order, allowing each resource's default translation; missing errors ultimately use their diagnostic fallback. Runtime fallback does not establish translation completeness.
- Bean Validation uses the same MessageSource. Write constraints such as `message="{iam.validation.username-required}"`, not hard-coded production text. Error patterns use `MessageFormat` arguments such as `{0}`; double apostrophes as `''` in parameterized sentences so arguments are not accidentally quoted as literals. Validation resources use constraint attributes such as `{min}`, `{max}`, and `{value}`. Do not concatenate whole sentences or pass passwords/tokens. Unresolved validation keys and internal binding details produce a localized generic 400; return-value validation and unexpected failures produce a generic 500. Servlet `/error` dispatches also return a localized JSON envelope, not Boot's default error object, HTML, or internal error details.
- `MessageCatalogTest` checks paired default/English files, unique and namespaced keys, nonblank text, placeholders, and common error coverage. New business errors must also verify that their keys exist. Public HTTP tests cover weighted/regional negotiation, missing translations, validation/authorization/framework errors, status codes, and Trace IDs, not only private translation helpers.
- IAM owns PostgreSQL account-language preferences; the framework does not query accounts on every locale resolution. Editable mail templates are notification business content: callers supply the recipient language, and unavailable custom templates fall back to the built-in template for the same purpose and language. Do not merge this with system messages or introduce implicit cross-module dependencies.

## 4. Database

Maintain PostgreSQL only, with a minimum version of 18. PostgreSQL 17 and earlier are unsupported; do not add a UUIDv7 generation fallback for them. Versioned migrations live under `freesia-admin/freesia-server/src/main/resources/db/migration/` as one linear history. Never edit an applied migration. Small account/role/menu/configuration tables remain ordinary tables.

Independent entity primary keys use native PostgreSQL `uuid`, generated by the database with `uuidv7()` by default and returned to Java as `UUID`; relationship tables may use composite keys. Entity IDs in all HTTP inputs and responses are UUID strings. Do not accept legacy numeric IDs, Base62 or Snowflake encodings, or generate entity IDs in the application. The frontend does not parse entity IDs as numbers or scan JSON text to repair legacy large IDs. Non-ID counts, sizes and durations stay JSON numbers; boxed `Long` is not globally serialized as a string. Defined nullable response fields return `null` unless the contract explicitly permits omission. Write contracts define omission and explicit clearing separately. Use database `timestamptz` and Java `Instant`, serialized as ISO-8601 UTC; clients choose the display zone, and mail/exports receive an explicit zone. Database uniqueness/not-null/required foreign keys enforce invariants.

Pin Flyway history with `spring.flyway.default-schema=public`. Business SQL explicitly qualifies the `freesia` schema, so creating a schema with the account's name cannot change the history location through `search_path` and cause repeated migrations.

Partition audit tables only with actual time queries and retention requirements. Unique keys include the partition key; define precreation, late records and sealing. Choose partial/GIN/BRIN indexes from actual SQL and query plans. BM25 requires an optional extension. Large logs use stable time-and-UUID cursors and database ordering for subsequent queries; clients only hold opaque cursors. Do not substitute Java `UUID.compareTo` signed ordering for PostgreSQL UUID ordering. Ordinary admin pages can use numbered pagination.

## 5. Authentication and RBAC

No tenants. The target is account → role → menu/button permission with built-in R_SUPER/R_ADMIN/R_USER codes. Role level limits administration, not implicit permission or inheritance. Keep one permission-code catalog shared by annotations, seed menus, buttons and routes.

Enforce authorization in the backend. Test disabled roles/accounts, revocation, privilege escalation and the last active super administrator. Authorization defaults to database reads. The current starter obtains permissions from `AuthorizationSource` and denies permission checks without an implementation. The future IAM `none|versioned` cache defaults to `none`; verify version checks, concurrent invalidation and stale refills with acceptance tests, not just TTL or broadcasts. Authorization-management writes always check the database directly. Implement each backend capability with its frontend login, menu and management flow. Do not add mock login, mock permissions or a default administrator token.

## 6. Frontend

Directory responsibilities are api/views/components/hooks/router/store/utils/types/locales/config. Product identity is centralized in `freesia-web/src/config/product.ts`; hide unset links and synchronize static HTML title/favicon. Use Freesia component/style prefixes and Freesia Admin storage keys. Responses use `code/msg/data`; pagination is `Api.Common.PaginatedResponse` / `Api.Common.CursorResponse`.

Feature pages cover authentication, accounts, roles, menus, files, mail, settings, audit and dashboard. Shared themes, layouts, charts, forms, media components, hooks, directives and utilities belong in their common directories. Coordinate frontend/backend contracts within each capability. Build, lint, formatting and commit tools live in the frontend package; exclude personal settings and obsolete scripts.

HTTP errors preserve status, business code, server `msg` and Trace ID. Sessions and dynamic menu registration use backend authentication and permissions. Public pages use static routes. Include the frontend LICENSE as `LICENSE.txt` in distribution.

Keep TypeScript strict; use unknown plus validation at uncertain boundaries and avoid new any. Follow formatter/lint conventions, use typed props/emits, and express loading/error/empty states. Frontend permissions govern presentation; backend decisions remain authoritative.

## 7. Dependencies and optional facilities

`freesia-dependencies` imports Boot/Modulith/Sa-Token BOMs and locks remaining versions. The reactor parent inherits this BOM and manages plugins, `release=25` and `-parameters`. Require Boot 4 and Spring 7; exclude Boot 3 and MyBatis-Plus. Commit the frontend lockfile and install frozen. A standard Caffeine cache manager is available at startup; business use is explicit, and queries or permissions are not automatically cached. Caffeine is bounded local caching; Valkey holds sessions/shared state; they do not automatically form a two-level cache.

DuckDB/ClickHouse versions are locked only in the BOM; they are absent from default dependencies, pools and jobs. Add them only for real business queries. DuckDB requires an optional dependency, explicit profile, conditional configuration and limits on threads/memory/temp storage; disabled adapters have no external startup dependency. Do not accept arbitrary user SQL. Verify archive files/manifests/time boundaries before deleting online data; define synchronization and freshness separately for real-time analytics.

## 8. Implementing capabilities

After foundation checks pass, implement one complete capability at a time within its module. Record frontend/backend contracts, dependencies and acceptance criteria. File storage, mail and TOTP require explicit behavior, configuration, endpoints, data, extension points and errors, with acceptance evidence in the capability PR.

Use Freesia identity consistently in product names, logos, mail subjects/templates, OpenAPI, logs, cache keys, Java packages, database/container/volume names and documentation links. Keep required third-party license and copyright notices with the corresponding components.

## 9. Validation and collaboration

Develop through task branches and PRs with manual maintainer merges using merge commits, squash or rebase, per [PR_SPEC.md](./PR_SPEC.md). Agents implement, validate and prepare authorized PRs for handoff; they do not commit/push main, merge or auto-merge. Current user limits such as “do not commit” or “do not push” take precedence.

Run `mvn test` in `freesia-admin/` for backend changes; SQL/transaction tests use real PostgreSQL. Module changes include structure checks and module integration tests where useful. Package with `mvn package` and start `freesia-server/target/freesia-admin.jar`; development commands are in the root README. Run `pnpm lint` and `pnpm build` in `freesia-web/`; UI changes need layout/interaction checks. Do not add implementation-mirroring tests for simple reversible edits.

Tests cover meaningful risks: rollback, authorization, pagination, partition pruning and module collaboration. Exclude build artifacts, dependencies, logs, credentials and temporary reference archives. Maintain root documentation in both languages and describe current behavior accurately. Follow the [Conventional Commits specification](./COMMIT_SPEC.md).
