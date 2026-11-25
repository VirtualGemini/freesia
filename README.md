# Freesia

English | [简体中文](./README.zh-CN.md)

In the backend, `freesia-server` handles startup and deployment configuration only.
Business code, migrations and resources live in `freesia-system`; shared technical
capabilities remain in `freesia-framework`.

Freesia is a modern administration application built with Vue 3, TypeScript and Spring Boot.

## Local development

Requirements: JDK 25, Maven 3.9+, Node 20.19+, pnpm 11 and Docker. Check `mvn --version` to confirm Maven uses JDK 25.

Prepare accessible PostgreSQL 18+ and Valkey services first. Local connections
use `application-dev.yml`, with default ports 15432 and 16379; override them
through `FREESIA_*` environment variables as needed. Then start the backend directly:

```sh
cd freesia-admin
mvn clean package -DskipTests
java -jar freesia-server/target/freesia-admin.jar
```

Start the frontend in another terminal:

```sh
cd freesia-web
pnpm install --frozen-lockfile
pnpm dev
```

Frontend: http://localhost:3000. Backend: http://localhost:8080. Vite proxies `/api` to the backend. Local PostgreSQL and Valkey use ports 15432 and 16379; backend configuration can be overridden with `FREESIA_*` environment variables.

### Container testing and production configuration

Local development keeps the workflow above: run both applications directly and use `application-dev.yml` by default. The root `docker-compose.yml` builds the complete container environment. Testing and production share that file; select `--env-file docker.env` for testing or `--env-file docker.prod.env` for production. No additional `.env` file is needed:

| Workflow | Configuration | Backend profile | Applications and dependencies |
| --- | --- | --- | --- |
| Local development/testing | `application-dev.yml` + local processes | `dev` | Local frontend/backend + independently prepared PostgreSQL/Valkey |
| Container testing | `docker.env` | `test` | Container frontend/backend + independent PostgreSQL/Valkey |
| Production deployment skeleton | `docker.prod.env` | `prod` | Container frontend/backend + independent PostgreSQL/Valkey |

Build and start the test environment from the repository root:

```sh
docker compose --env-file docker.env config --quiet
docker compose --env-file docker.env up -d --build --wait
# Explicitly initialize the first administrator with hidden terminal input.
docker compose --env-file docker.env run --rm freesia-admin bootstrap-admin --username=your-admin
docker compose --env-file docker.env logs -f freesia-admin
```

Open http://127.0.0.1:18080. Nginx proxies `/api` on the same origin and supports direct Vue history-route refreshes. Backend, database and Valkey ports are not published, and Actuator is not exposed through the frontend. `up --wait` checks dependency/application readiness; it neither creates an administrator nor validates account credentials.

The two environment files select the project name, backend profile, entry point, credentials, initialization language and proxy network. Compose uses `--env-file` for interpolation and maps only explicit values into the backend; it does not pass the whole file to the frontend build. Test passwords are examples only. Deployment environment variables override file values; do not commit real secrets.

`freesia-test` and `freesia-prod` use separate container, network and volume names and do not manage local development dependencies. Stop testing with `docker compose --env-file docker.env down`, which retains volumes. `down -v` deletes that environment's data and must not be used for retained databases. PostgreSQL initialization credentials apply only to an empty volume; changing environment variables does not reset an existing account's password.

A separate subnet and fixed Nginx address let the backend trust only the actual proxy. If the subnet overlaps host/VPN routes, change both `FREESIA_PROXY_SUBNET` and `FREESIA_PROXY_IP` in the selected file. Nginx overwrites client-supplied forwarded headers rather than trusting arbitrary chains. This configuration identifies direct test-entry clients; an additional production HTTPS gateway needs its own explicit client-address/trust-chain design.

Production configuration is a skeleton only; production deployment is not performed here. Database and Valkey passwords in `docker.prod.env` are empty. Inject `FREESIA_DB_PASSWORD` and `FREESIA_REDIS_PASSWORD` through a protected deployment environment. `config` validates syntax only: empty credentials cannot initialize a fresh PostgreSQL volume, and Valkey refuses to start without authentication. Production commands:

```sh
docker compose --env-file docker.prod.env config --quiet
docker compose --env-file docker.prod.env up -d --build --wait
```

Production HTTP binds to `127.0.0.1:18081` for a server-side HTTPS entry point. Complete TLS, backups and production operations before a real deployment. Do not expose the unencrypted HTTP entry point directly to the Internet.

PostgreSQL 18 is the minimum supported version. Entity IDs follow the database-generated UUIDv7 contract and use UUID strings over HTTP; non-ID values such as counts stay numeric. Legacy data and ID encodings are not supported.

Backend errors and validation messages use `Accept-Language` (`zh`/`en`, default `zh`) and module-owned resource bundles. Backend conventions are in the [development specification](./specs/DEVELOPMENT_SPEC.md#31-backend-internationalization).

### Environment configuration and development databases

Deployment configuration lives under `freesia-server/src/main/resources/`.
`application.yml` contains shared settings; `application-dev.yml`,
`application-prod.yml` and `application-test.yml` supply development, production
and test connections. Without an explicit selection, `dev` connects to
independently prepared local dependencies.

Select an environment with `SPRING_PROFILES_ACTIVE=prod` or
`--spring.profiles.active=prod`; select `test` for testing, and choose one at a
time. Both `prod` and `test` require `FREESIA_DB_URL`, `FREESIA_DB_USERNAME`,
`FREESIA_DB_PASSWORD` and `FREESIA_REDIS_HOST`; `FREESIA_REDIS_PORT` defaults to
6379. Production also requires an explicit `FREESIA_REDIS_PASSWORD` (explicitly
empty without authentication); test defaults to no authentication. Neither
falls back to the development database. Use the same environment and
connections for normal startup and `bootstrap-admin`.

Initialization SQL lives under `freesia-system/src/main/resources/db/migration/`:

- `zh/V1__create_product_schema.sql`: Chinese SQL documentation, table/column
  comments and role/menu seed labels (the default).
- `en/V1__create_product_schema.sql`: English comments and English seed labels.

These are language alternatives for the same baseline; execute only one. Set
`FREESIA_DB_LANGUAGE=en` before initialization for English (`zh` for Chinese),
or point the shared `spring.flyway.locations` at one language directory.
Never select both or scan their parent. Keep the same selection for startup
and `bootstrap-admin`, for example:

```sh
export FREESIA_DB_LANGUAGE=en
# Initialize with the English baseline and create the first administrator.
java -jar freesia-server/target/freesia-admin.jar bootstrap-admin --username=your-admin
java -jar freesia-server/target/freesia-admin.jar
```

This choice affects database comments, built-in display labels and the profile
language default, not role codes, permission codes, routes or HTTP
`Accept-Language` negotiation. Switching scripts does not translate an existing
database and fails validation.

The current unreleased development phase maintains only this V1 per language.
Update both variants together rather than adding migration versions. Disposable
development databases that applied an older baseline must be manually recreated
and the administrator bootstrapped again, only after confirming their data may
be discarded. Never do this to databases whose data must be retained, or bypass
differences with Flyway repair/disabled validation. Run `mvn clean package` after
consolidating/moving migrations to remove stale build resources. Freeze the baseline
for formal releases and use versioned incremental migrations thereafter.

### Organizations, accounts and delegated authorization

System management provides organization trees, real account queries/creation/profile editing, role configuration and independent organization transfer. Organizations have no status. Each role has one data scope, separate usable menu permissions and grantable permissions, six scope choices and per-function unions. The single built-in owner role and exclusive root cannot be assigned again; ordinary roles have neither levels nor organization ownership.

Administrators enter an initial nonblank password preserved verbatim, without a mandatory first-login change; accounts may start with no roles. Role member lists show only query-visible identities and the visible count. Account lifecycle/deletion, temporary-password recovery, full audit browsing and file/mail capabilities remain separate subsequent capabilities; demos and disconnected entries are not working business flows.

Custom-role single and batch deletion require an impact preview and confirmation. The preview shows the selected roles, read-only account-query-visible members and deduplicated visible counts; only ALL account-query scope declares the list complete. Other scopes never disclose outside identities or counts, and the visible count is not the total impact. Deletion removes every binding, including outside the query scope, without requiring control over all holders. A five-minute opaque confirmation is bound to the actor, targets and impact; the server rechecks current M/G/data scope and the complete snapshot under the management lock. Changed or expired previews reject the whole request (`409` / `10017`) and require a new preview. Any built-in, missing or unauthorized target rejects the batch. Role deletion, member/M/G/scope unbinding and per-role success audit share one PostgreSQL transaction; failed attempts are audited independently. Existing sessions lose that role's authorization on their next request, while other valid sources remain effective.

Menu management reuses the existing tree table and editor for queries, creation, updates, status, sorting and deletion; resolve child nodes before deleting a parent. Menu definitions are global shared resources, so each maintenance action requires its own function and ALL data scope. Account navigation still exposes only usable menus; reading the directory or holding grant permissions does not expand it. External-link changes and clearing persist, and clearing disables iframe mode; non-directory pages need an internal component or redirect. Omitted update fields retain their values rather than treating an explicit clear as no change.

### Administrator initialization and password login

Explicitly initialize the first super administrator before starting the server
(the terminal hides password input; plaintext `--password` is rejected):

```sh
java -jar freesia-server/target/freesia-admin.jar bootstrap-admin --username=your-admin
```

For non-interactive deployment, add `--password-file=/run/secrets/freesia-admin-password`
and protect/remove the initialization secret after success. Repeated/concurrent
execution does not reset the account. There is no default account, password or
administrator token.

Initialization and password login require only a nonempty, non-whitespace
password, without length or character-composition rules. Empty interactive input
prints a clear message and prompts again with hidden input, without a retry
limit; press Ctrl+C to cancel. An empty non-interactive secret file fails with
an explicit error instead of waiting for terminal input.

Password login, current-account/database navigation and current-session logout
are connected with synchronous authentication auditing. Access defaults to 30
minutes and a fixed session deadline of 14 days. Refresh rotation replaces both
credentials without changing the session ID or extending that deadline; an old
Refresh credential cannot be reused. Previously issued Access credentials keep
their own expiry, but all become unusable when their session is revoked.
Configure Argon2id costs/concurrency under `freesia.iam.password`. Password failures
are limited per username/IP. Configure `freesia.web.trusted-proxies` only for known
proxies; forwarded headers are ignored by default.

The browser keeps Access only in memory. Login and refresh return Access and
session metadata, never Refresh plaintext; the persistent, host-only
`freesia_refresh` HttpOnly cookie uses `SameSite=Strict` and `Path=/api/auth`.
Keep `freesia.iam.browser-session.cookie-secure=true` behind production HTTPS;
`FREESIA_REFRESH_COOKIE_SECURE` can override it, while local HTTP
development/testing explicitly disables Secure. Login, refresh and
logout require `X-Freesia-CSRF: 1`; do not enable cross-origin credential access
to these endpoints.

The browser restores a valid session from the cookie and reloads account/menu
permissions. Only an explicit Access-expired response (`401` / `10002`) permits
one refresh and one continuation of the unexecuted request. Same-page callers
share refresh work; cross-tab authentication changes are coordinated with Web
Locks and ephemeral BroadcastChannel messages, not stored credentials. Use a
browser supporting Web Locks over HTTPS (or loopback HTTP in development).
Ordinary network errors, 503 and an uncertain refresh response do not
automatically replay business requests or clear login.
Confirmed session invalidation (`401` / `10003`) ends the affected browser
session. Logout is synchronized across tabs, not other devices.

Refresh/logout use `X-Freesia-Session` to select the expected session alongside
an authenticated cookie or Access proof; the session ID is not a credential.
If a strict one-use refresh commits but its replacement cookie is lost, signing
in again may be necessary. A late response can still overwrite a fixed-name
browser cookie; session selection prevents silently restoring another account,
not every cookie-availability failure.

Valkey is required at startup. Runtime failure keeps liveness available but
returns 503 for readiness and dependent requests; recovery is automatic. Probe
paths are `/actuator/health/liveness` and `/actuator/health/readiness`.

Offline IPv4/IPv6 location uses the two historical xdb files. Bundled
`ip2region/README.md` records their provenance, checksums and rights limitations;
third-party licenses are under `META-INF/licenses/`. Trusted local `file:`
resources can replace them through `freesia.iam.ip-location`. No automatic
download or update is performed.

The project is under development. `/api/product` provides product information;
account/role/menu editing and other management capabilities are being connected
incrementally. Existing dashboard demos remain demos, not live metrics.

## Checks

```sh
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
```

Backend tests explicitly use the `test` environment and temporary PostgreSQL/Valkey containers, not the local development database. Frontend checks include ESLint, TypeScript and a production build.

## Documentation

- [Frontend development](./freesia-web/README.md)
- [Development specification](./specs/DEVELOPMENT_SPEC.md)
- [Commit specification](./specs/COMMIT_SPEC.md)
- [PR workflow](./specs/PR_SPEC.md)
- [Contributing](./CONTRIBUTING.md)

## License

See [backend LICENSE](./freesia-admin/LICENSE) and [frontend LICENSE](./freesia-web/LICENSE). Frontend builds include `LICENSE.txt`.
