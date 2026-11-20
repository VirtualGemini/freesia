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

The project is under development. Product information is available at `/api/product`; authentication and management APIs are still being implemented.

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
