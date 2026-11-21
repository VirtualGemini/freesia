# Freesia

English | [简体中文](./README.zh-CN.md)

Freesia is a modern administration application built with Vue 3, TypeScript and Spring Boot.

## Local development

Requirements: JDK 25, Maven 3.9+, Node 20.19+, pnpm 11 and Docker. Check `mvn --version` to confirm Maven uses JDK 25.

Start the backend:

```sh
docker compose -f infra/docker-compose.yml up -d
cd freesia-admin
mvn package -DskipTests
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

Backend tests use temporary PostgreSQL and Valkey containers. Frontend checks include ESLint, TypeScript and a production build.

## Documentation

- [Frontend development](./freesia-web/README.md)
- [Development specification](./specs/DEVELOPMENT_SPEC.md)
- [Commit specification](./specs/COMMIT_SPEC.md)
- [PR workflow](./specs/PR_SPEC.md)
- [Contributing](./CONTRIBUTING.md)

## License

See [backend LICENSE](./freesia-admin/LICENSE) and [frontend LICENSE](./freesia-web/LICENSE). Frontend builds include `LICENSE.txt`.
