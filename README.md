# Freesia

English | [简体中文](./README.zh-CN.md)

In the backend, `freesia-server` handles startup and deployment configuration only.
Business code, migrations and resources live in `freesia-system`; shared technical
capabilities remain in `freesia-framework`.

Freesia is a modern administration application built with Vue 3, TypeScript and Spring Boot.

## Local development

Requirements: JDK 25, Maven 3.9+, Node 20.19+, pnpm 11 and Docker. Check `mvn --version` to confirm Maven uses JDK 25.

Start the backend:

```sh
docker compose -f infra/docker-compose.yml up -d
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

PostgreSQL 18 is the minimum supported version. Entity IDs follow the database-generated UUIDv7 contract and use UUID strings over HTTP; non-ID values such as counts stay numeric. Legacy data and ID encodings are not supported.

Backend errors and validation messages use `Accept-Language` (`zh`/`en`, default `zh`) and module-owned resource bundles. Backend conventions are in the [development specification](./specs/DEVELOPMENT_SPEC.md#31-backend-internationalization).

### Environment configuration and development databases

Deployment configuration lives under `freesia-server/src/main/resources/`.
`application.yml` contains shared settings; `application-dev.yml`,
`application-prod.yml` and `application-test.yml` supply development, production
and test connections. Without an explicit selection, `dev` uses local Compose.

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

Password login, current-account/database navigation and current-session logout
are connected with synchronous authentication auditing. Access defaults to 30
minutes and a fixed session deadline of 14 days. This slice issues an initial
Refresh credential; rotation and browser continuation are separate capabilities.
Configure Argon2id costs/concurrency under `freesia.iam.password`. Password failures
are limited per username/IP. Configure `freesia.web.trusted-proxies` only for known
proxies; forwarded headers are ignored by default.

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
