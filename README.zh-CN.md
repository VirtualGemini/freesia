# Freesia

[English](./README.md) | 简体中文

Freesia 是使用 Vue 3、TypeScript 和 Spring Boot 构建的现代化管理系统。

## 本地运行

需要 JDK 25、Maven 3.9+、Node 20.19+、pnpm 11 和 Docker。通过 `mvn --version` 确认 Maven 使用 JDK 25。

启动后端：

```sh
docker compose -f infra/compose.yml up -d
cd freesia-admin
mvn package -DskipTests
java -jar freesia-server/target/freesia-admin.jar
```

另一个终端启动前端：

```sh
cd freesia-web
pnpm install --frozen-lockfile
pnpm dev
```

前端：`http://localhost:3000`；后端：`http://localhost:8080`。Vite 将 `/api` 代理到后端。本地 PostgreSQL 和 Valkey 使用 15432、16379 端口，后端配置可通过 `FREESIA_*` 环境变量覆盖。

项目正在开发中。`/api/product` 提供产品信息，认证和管理 API 尚在实现中。

## 验证

```sh
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
```

后端测试使用临时 PostgreSQL 和 Valkey 容器。前端检查包含 ESLint、TypeScript 和生产构建。

## 文档

- [前端开发](./freesia-web/README.zh-CN.md)
- [开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md)
- [提交规范](./specs/COMMIT_SPEC.zh-CN.md)
- [PR 流程](./specs/PR_SPEC.zh-CN.md)
- [贡献指南](./CONTRIBUTING.zh-CN.md)

## 许可证

见[后端 LICENSE](./freesia-admin/LICENSE)和[前端 LICENSE](./freesia-web/LICENSE)。前端构建产物包含 `LICENSE.txt`。
