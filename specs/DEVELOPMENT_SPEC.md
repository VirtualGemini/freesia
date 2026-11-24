# Freesia Development Specification

English | [简体中文](./DEVELOPMENT_SPEC.zh-CN.md)

These are the development conventions for Freesia. Build the shared foundation before implementing business capabilities.

## 1. Product and repository

The product is Freesia and the administration app is Freesia Admin. `freesia-admin/` is the Java 25 / Spring Boot 4.1 Maven reactor: dependency BOM, framework foundation, system business implementation, executable server assembly and optional extensions. `freesia-web/` uses Vue 3, TypeScript, Vite, Element Plus and Pinia. `infra/` owns local PostgreSQL/Valkey orchestration; Flyway owns business schema. `specs/` holds tracked conventions and architecture; task scope and validation belong in the PR or related GitHub issue. `docs/` contains ignored local skill working material. Package-specific tooling stays in its package.

## 2. Backend organization

`freesia-server` is the executable startup module only. Production code contains only `com.freesia.admin.FreesiaAdminApplication`; production resources contain only deployment configuration `application.yml`, `application-dev.yml`, `application-prod.yml` and `application-test.yml`. It starts/imports business assembly and produces `freesia-admin.jar`, without business controllers, use cases, mappers, SQL, translations, offline datasets or business-specific dependencies.

Keep shared configuration in `application.yml` and default local development with `spring.profiles.default=dev`, not a fixed `spring.profiles.active` that prevents deployment selection. Put connections in the environment files and explicitly select `prod` or `test` with `SPRING_PROFILES_ACTIVE` or `--spring.profiles.active`, choosing one deployment environment at a time. Only `dev` supplies local Compose endpoints and development credentials. `prod`/`test` require dedicated database connections, credentials and a Valkey host, without development fallbacks. Production requires an explicit Valkey password, set explicitly empty when authentication is disabled. Automated tests explicitly use `test` and temporary containers rather than local services. Splitting environments must not weaken shared internationalization, security, migration or probe conventions.

`freesia-system` is a regular library JAR rooted at `com.freesia.system`, assembled by `FreesiaSystemConfiguration`. Its direct business packages remain `iam`, `file`, `notification`, `audit`, `dashboard`; the Maven split does not change domain boundaries. Sign-in policy, verification rules and TOTP belong to IAM. Module roots contain only `package-info.java`; Java contracts, DTOs and events exposed to other modules or the startup module live in `api/`, declared with `@NamedInterface("api")`, and implementation lives under `internal/<capability>/`. `api/` is not the HTTP layer: controllers and capability-local request/response DTOs are internal implementation. Owned tables, SQL, message resources, offline datasets and corresponding third-party notices ship in the system JAR.

Dependencies flow `server → system → framework/common`. System and framework never reference the executable server. System integration tests use their own test-only application assembly, without a reverse dependency for tests. Server tests verify actual startup assembly and artifact ownership.

Framework packages are `com.freesia.common` / `com.freesia.framework.*`. They provide technical configuration without business tables/SQL or references to `com.freesia.system..` or `com.freesia.admin..`. The three starters have no production dependencies on each other; within this project they depend only on common, while tests may compose starters. Extensions provide connections/resource limits; business QueryPorts and SQL stay in the owning system module.

Cross-module access uses named APIs. Declare allowed dependencies in package-info, for example `"iam :: api"`, when an actual call exists. Do not access another module's internal entities, mappers or tables. Notification callers supply the recipient language; notification does not query accounts. Modulith verification and the framework-to-business dependency check are required.

### 2.1 Capability and responsibility packages across system

Organize all of system as **business module → capability → responsibility**, without restoring elaborate DDD `domain/application/infrastructure` layers or adding Maven modules for package organization. Identify the owning capability first, then split by actual responsibility. Never gather every controller, service or mapper directly under a business module.

The current implementation follows this layout. Braces list sibling responsibility packages, not mandatory layers for every capability:

```text
com.freesia.system/
├── FreesiaSystemConfiguration
├── iam/
│   ├── api/
│   └── internal/
│       ├── account/{controller,dto,entity,mapper,query}
│       ├── auth/{controller,dto,filter,service}
│       ├── authorization/{controller,query}
│       ├── availability/{controller,service}
│       ├── bootstrap/{mapper,service}
│       ├── config/
│       ├── menu/{controller,dto,query}
│       ├── origin/{config,service}
│       ├── password/{config,service}
│       ├── role/{controller,dto,query}
│       └── session/{config,dto,query,service}
├── audit/
│   ├── api/
│   └── internal/
│       ├── config/
│       ├── login/{controller,dto,mapper,query,service}
│       └── operation/{mapper,service}
├── dashboard/
│   ├── api/
│   └── internal/product/{controller,dto}
├── file/{api,internal}
└── notification/{api,internal}
```

| Responsibility package | Contents |
| --- | --- |
| `controller` | HTTP routes, validation, request/response conversion and corresponding exception handling, without SQL. Cookie/HTTP helpers used only by controllers may stay beside them. |
| `service` | Concrete use cases, business rules, transaction entry points and capability implementation. Use names such as `PasswordLogin` and `InitializeAdministrator`; a `Service` suffix is not mandatory. |
| `query` | JdbcClient queries and result assembly, such as `AccountQueries` and `RoleQueries`, without controller annotations or HTTP request objects. |
| `mapper` | MyBatis-Flex write mappers with SQL owned by the capability. Initialization markers and first-role binding belong to `bootstrap`, not a generic account mapper. |
| `entity` | Database row mappings such as `AccountRow`, not artificial behaviorless domain wrappers. |
| `dto` | Explicit capability-local requests, responses and data crossing responsibility packages. A Java `public` type does not belong in module `api/` for that reason alone. Results/helper records used only by one class may remain nested. |
| `config` | Capability configuration properties and bean assembly. Only module-wide assembly belongs in `internal/config`, for example module mapper scanning. |
| `filter` | Capability-specific Servlet filters such as credential-entry protection, not a system-wide folder for business filters. |

Authentication requests and password-failure counting belong to `auth`; Access/Refresh lifecycle and session policy to `session`; account queries to `account`; roles to `role`; menu navigation to `menu`; and permission decisions to `authorization`. Password hashing, source IP/offline location, and availability belong to `password`, `origin`, and `availability` respectively. Split auditing by capabilities such as login and operation; do not collect every audit write/listener under `login`.

Create packages only for existing responsibilities, without empty directories, services, placeholder mappers or forwarding layers. HTTP handlers may call their query/use case directly rather than introducing a pass-through service. Unimplemented file/notification modules retain their module boundaries without speculative storage/template capabilities.

Capabilities within the same module may directly use necessary internal types while keeping dependencies clear. Prefer package-private visibility and use `public` only for cross-package calls or framework requirements. Java `public` does not bypass Spring Modulith's `internal` restrictions. Avoid global responsibility packages, mandatory interface/`Impl` pairs and Repository/Manager/Facade abstractions without a real variation point. Package moves must update imports, mapper scanning and actual assembly; run `mvn clean test` so stale bytecode cannot hide missed moves. `ModuleStructureTest` checks capability-first layout, HTTP/query/persistence ownership and existing module dependencies.

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
- Common owns `i18n/common/messages.properties` (the Simplified Chinese default) and `messages_en.properties`. Shared system resources live under system `i18n/messages*`; each business module owns `i18n/<module>/messages*`. Business keys use the `iam/file/notification/audit/dashboard` namespaces rather than accumulating in common. Other categories are `.validation.`, `.message.`, and `.export.`.
- Register actual resource paths explicitly through `spring.messages.basename`, using a list or comma-separated values. Each basename needs a default file with a complete English counterpart. The starter appends common resources after application basenames and exposes one injectable MessageSource. Application basename order defines precedence; business modules must not duplicate keys.
- Use `spring.messages.encoding=UTF-8`, no host-locale fallback, and no raw-key responses (`fallback-to-system-locale=false`, `use-code-as-default-message=false`; the default configuration enforces both). Resolve resources in order, allowing each resource's default translation; missing errors ultimately use their diagnostic fallback. Runtime fallback does not establish translation completeness.
- Bean Validation uses the same MessageSource. Write constraints such as `message="{iam.validation.username-required}"`, not hard-coded production text. Error patterns use `MessageFormat` arguments such as `{0}`; double apostrophes as `''` in parameterized sentences so arguments are not accidentally quoted as literals. Validation resources use constraint attributes such as `{min}`, `{max}`, and `{value}`. Do not concatenate whole sentences or pass passwords/tokens. Unresolved validation keys and internal binding details produce a localized generic 400; return-value validation and unexpected failures produce a generic 500. Servlet `/error` dispatches also return a localized JSON envelope, not Boot's default error object, HTML, or internal error details.
- `MessageCatalogTest` checks paired default/English files, unique and namespaced keys, nonblank text, placeholders, and common error coverage. New business errors must also verify that their keys exist. Public HTTP tests cover weighted/regional negotiation, missing translations, validation/authorization/framework errors, status codes, and Trace IDs, not only private translation helpers.
- IAM owns PostgreSQL account-language preferences; the framework does not query accounts on every locale resolution. Editable mail templates are notification business content: callers supply the recipient language, and unavailable custom templates fall back to the built-in template for the same purpose and language. Do not merge this with system messages or introduce implicit cross-module dependencies.

## 4. Database

Maintain PostgreSQL only, with a minimum version of 18. PostgreSQL 17 and earlier are unsupported; do not add a UUIDv7 generation fallback for them. SQL lives under `freesia-admin/freesia-system/src/main/resources/db/migration/`, loaded by the server from the dependency JAR's classpath.

**The current unreleased development phase maintains exactly one initialization migration per language, `V1__create_product_schema.sql`.** Chinese lives in `db/migration/zh/` and English in `db/migration/en/`. They are mutually exclusive alternatives for the same baseline, not two migration steps. Update their tables, indexes, constraints and seeds together; do not add V2/V3 files for development steps. Either language must completely initialize an empty database.

Use localized section comments and PostgreSQL `COMMENT ON SCHEMA/TABLE/COLUMN` to persist queryable descriptions for the schema, every table and every column. Explain business semantics, units, enumerated values, nullability and important defaults for constraints/indexes/seeds rather than merely restating types. The English variant has English comments and role/menu/button labels; the Chinese variant uses Chinese. The profile language defaults to `en`/`zh` respectively. Structures, constraints, grants and stable business codes remain equivalent; only comments, display labels and the profile-language default may differ. Real PostgreSQL tests verify comment coverage and consistency.

`spring.flyway.locations=classpath:db/migration/${FREESIA_DB_LANGUAGE:zh}` selects Chinese by default. Set `FREESIA_DB_LANGUAGE=en` before initialization for English, or specify one `spring.flyway.locations` directly. Never scan their common parent or select both. Keep `fail-on-missing-locations=true` so an invalid language/path fails. Startup and `bootstrap-admin` must select the same variant. Changing an initialized database's language fails validation; this is not online translation or business-data rewriting. Installation language is independent of runtime `Accept-Language` message negotiation.

Upgrades from previous disposable development databases are not supported. A database that applied an older baseline fails Flyway validation. A developer may manually recreate its dedicated database and bootstrap the administrator only after confirming the data is disposable. Back up databases whose data must be retained and plan their migration separately. Never automatically drop databases, disable validation or use repair to conceal differences. Run `mvn clean` after consolidating/moving/removing migrations so old SQL cannot remain in build output.

Once formal releases and versioned iterations begin, freeze the released baseline/applied migrations and add subsequent versions in one linear history. The development-only single-file rule never permits rewriting production history. Small account/role/menu/configuration tables remain ordinary tables.

Independent entity primary keys use native PostgreSQL `uuid`, generated by the database with `uuidv7()` by default and returned to Java as `UUID`; relationship tables may use composite keys. Entity IDs in all HTTP inputs and responses are UUID strings. Do not accept legacy numeric IDs, Base62 or Snowflake encodings, or generate entity IDs in the application. The frontend does not parse entity IDs as numbers or scan JSON text to repair legacy large IDs. Non-ID counts, sizes and durations stay JSON numbers; boxed `Long` is not globally serialized as a string. Defined nullable response fields return `null` unless the contract explicitly permits omission. Write contracts define omission and explicit clearing separately. Use database `timestamptz` and Java `Instant`, serialized as ISO-8601 UTC; clients choose the display zone, and mail/exports receive an explicit zone. Database uniqueness/not-null/required foreign keys enforce invariants.

Pin Flyway history with `spring.flyway.default-schema=public`. Business SQL explicitly qualifies the `freesia` schema, so creating a schema with the account's name cannot change the history location through `search_path` and cause repeated migrations.

Partition audit tables only with actual time queries and retention requirements. Unique keys include the partition key; define precreation, late records and sealing. Choose partial/GIN/BRIN indexes from actual SQL and query plans. BM25 requires an optional extension. Large logs use stable time-and-UUID cursors and database ordering for subsequent queries; clients only hold opaque cursors. Do not substitute Java `UUID.compareTo` signed ordering for PostgreSQL UUID ordering. Ordinary admin pages can use numbered pagination.

## 5. Authentication and RBAC

No tenants. Use account → role → menu/button RBAC. Each role has one data scope D and independent use permissions M and grant permissions G. R_SUPER is the only built-in role and belongs to the unique initial owner. There are no account/role levels, role organization ownership or special sub-administrator identity. Organizations carry the tree and data scope, with no status or enable/disable operation.

- The six scopes are self, descendants excluding the current organization, current organization, current organization with descendants, explicit organizations, and all data. Relative scopes use the actual holder's current organization. Accounts without an organization cannot hold relative-organization roles; remove incompatible bindings before clearing organization. Custom organization selection defaults to linked parent/children but supports independent selection; save checked nodes only, not half-checked parents or future children.
- Merge scope per granted function across effective roles, never across unrelated functions. Merge M and G independently. Ordinary operations use their own M plus D, without peer-write bans, target-full-profile containment or organization permission ceilings. G limits grantable functions without requiring the actor to hold their M; do not introduce a second administrator assignment-scope configuration.
- Role directories/details expose only roles whose complete M and G the actor may manage. Check both old and new content on edits, current content on state changes, and authoritative state on submission. Binding/unbinding is a delta preserving unrelated bindings; self-assignment follows the same rules and creating a role never binds it automatically. Shared roles do not require control of every holder. Read-only member lists expose only query-visible identities and the visible count.
- Accounts have one optional organization. Organization transfer has an independent permission; check source, destination and changing scope using pre-transfer rights. Allow authorized self-transfer, preserve role bindings, and do not impose subtree permission ceilings when reparenting. Reject organization deletion with children, account references or effective custom-scope references.
- The root and R_SUPER binding are exclusive and cannot be assigned again. The unique owner cannot be disabled, deleted, moved out of root or unbound; root and the built-in role cannot be disabled/deleted. Only the owner may edit root name/remark; its identity, code and parent are fixed. All-data permissions do not authorize ordinary accounts to manage the owner; normal owner profile editing remains available.
- Account creation may leave roles empty. Administrators enter an initial nonblank password, preserved verbatim without strength checks, readback or mandatory first-login change. Existing-account recovery remains a separate system-generated temporary-password flow. General profile requests cannot change credentials, roles, organization or state.

Enforce authorization in the backend; hidden menus, frontend buttons and restored tabs are not security checks. Keep function codes consistent across backend, seeds and frontend. Read permissions and sensitive checks from the primary database; management transactions recheck authoritative state before writes, commit successful audit atomically and record failures independently after rollback. AuthorizationSource supplies the technical entry's function set; IAM owns D/G business rules without framework-to-system dependencies. Standard Caffeine facilities do not enable authorization caching. Future versioned caching must prove version checks, concurrent invalidation and stale-refill protection, not just TTL/broadcasts.

Implement each capability with its real frontend flow and public behavior tests, preserving existing frontend architecture and demos. No mock login, mock permissions or default administrator token.

### 5.1 Password authentication foundation

- Create the first super administrator only with the explicit server-side `bootstrap-admin` command. Read its password from hidden console input or a secret file, never a plaintext argument or log. A locked persistent initialization marker prevents duplicate/concurrent resets. Migrations seed the exclusive root, the unique built-in role, minimal navigation and policies, not accounts.
- Use only self-describing Argon2id PHC hashes. Defaults are 65536 KiB / 3 iterations / 4 lanes under `freesia.iam.password`, with bounded computation concurrency. Successful-login upgrades take the non-decreasing maximum of each stored/configured cost; mixed changes cannot lower a dimension. Do not accept legacy algorithms. Spring crypto provides password functions, not a second authentication framework.
- PostgreSQL owns Access/session policy, defaulting to 30 minutes / 14 days. Each login receives an independent stable sessionId and random Access/Refresh credentials; the absolute deadline is fixed at login. Valkey stores only the Refresh digest; no historical session table. Access expiry and session revocation are distinct. Initial issuance does not imply that the separate refresh-rotation capability is complete.
- Authentication codes: `10001`/400 is neutral credential failure; `10002`/401 is explicit Access expiry before handler execution; `10003`/401 is session invalidation; `10004`/429 is a paused username/IP pair; `10005`/503 is exhausted password capacity. Database/Valkey unavailability uses common 503, never 401. Network failures must not clear browser credentials.
- The default username/trusted-source-IP rule pauses a pair for 15 minutes after 5 failures in 15 minutes. Policies live in PostgreSQL and atomic counters/transitions in Valkey. Success before the threshold clears the count. Do not globally lock accounts or affect other sources/established sessions. Read account/role state and permissions from the database; role names are not permission bypasses.
- Commit synchronous successful-login audit before activating or exposing credentials; compensation revokes only the new session, without Sa-Token's default session-count eviction. Logout revokes current IAM session/Refresh mappings first, so failed Sa-Token cleanup cannot restore access. Expired Access may only revoke a session before its absolute deadline, never execute business operations. Valkey retains digest-addressed logout retry context and minimal snapshots for at most 24 hours; this cannot restore credentials. Audit retries deduplicate by sessionId and are not a durable delivery queue. Common contains only minimal audit data; audit persists synchronous events without business-module cycles. Successful management audit must join the business transaction; failures are recorded independently after rollback.
- Valkey is required at startup. Runtime failure leaves the process alive but unready, returns 503 for dependent requests and recovers automatically. Never fall back to memory. Liveness excludes external dependencies; readiness checks the database and Valkey.
- Login, throttling and auditing share a literal-only client-IP resolver: trust the direct peer unless its proxy CIDR is explicitly configured in `freesia.web.trusted-proxies`; never resolve DNS. Reuse the approved historical offline IPv4/IPv6 xdb files only as auxiliary audit data. Retain provenance, digests and third-party notices; do not claim current accuracy, physical location or that the library license certifies every data input's rights. Use bounded exclusive Searchers without a result cache; unavailable, missing or saturated lookups retain the IP with an unknown location.
- The login slider is interaction only: no backend ticket request or ineffective remember-password checkbox. Unimplemented registration/mail/MFA flows cannot return fake success. Preserve the demo dashboard and frontend architecture.

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
