# Freesia

[English](./README.md) | 简体中文

一个集强大和优雅于一身、更适合年轻人的现代化管理系统。

## 本地运行

当前后端底座使用 Java 25、Spring Boot 4.1 和 Spring Modulith，提供共同 Web、鉴权与数据设施及公开的 `/api/product` 接口。账号、角色、菜单、文件、通知和审计用例将在后续能力 PR 中实现。

需要 JDK 25、Maven 3.9+ 和 Docker。通过 `mvn --version` 确认 Maven 使用 JDK 25；macOS 默认 JDK 不同时，可设置 `JAVA_HOME=$(/usr/libexec/java_home -v 25)`。

```sh
docker compose -f infra/compose.yml up -d
cd freesia-admin
mvn package -DskipTests
java -jar freesia-server/target/freesia-admin.jar
```

产品接口：`http://localhost:8080/api/product`。本地 PG/Valkey 绑定 15432/16379，使用独立 Freesia 卷；配置通过 `FREESIA_*` 环境变量覆盖。Compose 默认配置用于本地开发。

Freesia Web 的 Vue/TypeScript 前端通过独立 PR 引入；恢复的管理页面随后端能力逐项接通。

## 验证与文档

```sh
cd freesia-admin
mvn verify
```

后端测试使用临时 PostgreSQL 和 Valkey 容器，需要 Docker；不操作本地业务数据库。reactor 验证共享事务、Web/鉴权行为与 Modulith 边界。使用 Maven 开发模式时，先执行 `mvn -pl freesia-server -am install -DskipTests`，再执行 `mvn -pl freesia-server spring-boot:run`。

仓库流程检查：`make check-policy`。发布任务分支前执行 `make check-commits BASE_REF=origin/main HEAD_REF=HEAD`。

- [开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md)
- [提交规范](./specs/COMMIT_SPEC.zh-CN.md)
- [PR 开发与维护者合并流程](./specs/PR_SPEC.zh-CN.md)
- [贡献指南](./CONTRIBUTING.zh-CN.md)

后端许可证见 [freesia-admin/LICENSE](./freesia-admin/LICENSE)；前端发布时随源码保留原许可证。
