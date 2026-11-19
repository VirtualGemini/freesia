# Freesia Admin

English | [简体中文](./README.zh-CN.md)

A modern management system that combines power and elegance, making it more suitable for young people.

## Local development

The backend foundation uses Java 25, Spring Boot 4.1 and Spring Modulith. It provides shared web, security and data infrastructure plus the public `/api/product` endpoint. Account, role, menu, file, notification and audit workflows will be implemented in later capability PRs.

Requirements: JDK 25, Maven 3.9+ and Docker. Ensure Maven uses JDK 25 with `mvn --version`; on macOS, set `JAVA_HOME=$(/usr/libexec/java_home -v 25)` when its default JDK differs.

```sh
docker compose -f infra/compose.yml up -d
cd freesia-admin
mvn package -DskipTests
java -jar freesia-server/target/freesia-admin.jar
```

Product endpoint: http://localhost:8080/api/product. PostgreSQL/Valkey use local ports 15432/16379 and dedicated Freesia volumes. Override configuration through `FREESIA_*` environment variables. The Compose defaults are for local development.

Freesia Web's Vue/TypeScript frontend will enter through a separate PR. Its restored management pages will be connected as backend capabilities become available.

## Validation and documentation

```sh
cd freesia-admin
mvn verify
```

Backend tests require Docker for temporary PostgreSQL and Valkey containers; they do not modify the local business database. The reactor checks shared transactions, web/security behavior and Modulith boundaries. For Maven development mode, first run `mvn -pl freesia-server -am install -DskipTests`, then `mvn -pl freesia-server spring-boot:run`.

Repository policy: `make check-policy`. Before publishing a task branch, run `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`.

- [Development specification](./docs/specs/DEVELOPMENT_SPEC.md)
- [Commit specification](./docs/specs/COMMIT_SPEC.md)
- [PR workflow and maintainer merges](./docs/specs/PR_SPEC.md)
- [Contribution guide](./CONTRIBUTING.md)

The backend's license is in [freesia-admin/LICENSE](./freesia-admin/LICENSE). The frontend's original license will accompany its source when published.
The [PR template](./.github/pull_request_template.md) retains its source attribution and Apache-2.0 license.
