# Freesia

[English](./README.md) | 简体中文

Freesia 是使用 Vue 3、TypeScript 和 Spring Boot 构建的现代化管理系统。

后端中，`freesia-server` 只负责启动和部署配置，业务代码、迁移及资源位于 `freesia-system`；通用技术能力仍在 `freesia-framework`。

## 本地运行

需要 JDK 25、Maven 3.9+、Node 20.19+、pnpm 11 和 Docker。通过 `mvn --version` 确认 Maven 使用 JDK 25。

启动后端：

```sh
docker compose -f infra/docker-compose.yml up -d
cd freesia-admin
mvn clean package -DskipTests
java -jar freesia-server/target/freesia-admin.jar
```

另一个终端启动前端：

```sh
cd freesia-web
pnpm install --frozen-lockfile
pnpm dev
```

前端：`http://localhost:3000`；后端：`http://localhost:8080`。Vite 将 `/api` 代理到后端。本地 PostgreSQL 和 Valkey 使用 15432、16379 端口，后端配置可通过 `FREESIA_*` 环境变量覆盖。

PostgreSQL 最低支持版本为 18。实体 ID 契约为数据库生成的 UUIDv7，HTTP 使用 UUID 字符串；计数等非 ID 数值保持数字，不提供旧数据或旧 ID 编码兼容。

后端错误和校验文案按 `Accept-Language`（`zh`／`en`，默认 `zh`）及模块资源文件解析，约定见[开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md#31-后端国际化)。

### 环境配置与开发数据库

部署配置位于 `freesia-server/src/main/resources/`：`application.yml` 为公共配置，`application-dev.yml`、`application-prod.yml`、`application-test.yml` 分别提供开发、生产、测试环境连接配置。未指定环境时默认 `dev`，直接连接上述本地编排。

通过 `SPRING_PROFILES_ACTIVE=prod` 或 `--spring.profiles.active=prod` 切换环境；测试环境选择 `test`，一次只选择一个环境。`prod`／`test` 必须提供 `FREESIA_DB_URL`、`FREESIA_DB_USERNAME`、`FREESIA_DB_PASSWORD`、`FREESIA_REDIS_HOST`；`FREESIA_REDIS_PORT` 默认 6379。生产还须显式设置 `FREESIA_REDIS_PASSWORD`（无认证时显式设空），测试默认无认证；这些环境不会回退到开发库。普通启动和 `bootstrap-admin` 必须使用相同的环境与连接参数。

初始化 SQL 位于 `freesia-system/src/main/resources/db/migration/`：

- `zh/V1__create_product_schema.sql`：中文 SQL 说明、数据库表／字段注释、角色和菜单种子文案；默认选择。
- `en/V1__create_product_schema.sql`：英文注释及英文种子文案。

两份是同一基线的语言选项，只执行一份。首次初始化前设置 `FREESIA_DB_LANGUAGE=en` 选择英文（中文为 `zh`），也可修改公共配置的 `spring.flyway.locations` 指向单个语言目录；不要同时选择两版或扫描父目录。普通启动与 `bootstrap-admin` 保持相同选择，例如：

```sh
export FREESIA_DB_LANGUAGE=en
# 使用英文版初始化，并创建首位超管
java -jar freesia-server/target/freesia-admin.jar bootstrap-admin --username=your-admin
java -jar freesia-server/target/freesia-admin.jar
```

选择影响数据库注释、内置显示文案和账号资料默认语言，不改变角色编码、权限码、路由或 HTTP `Accept-Language` 协商。已有数据库不能靠切换脚本翻译，切换会校验失败。

当前未正式发布的开发阶段每种语言只维护这份 V1，数据库变更同步更新两版，不新增迁移版本。已应用旧基线的临时开发库需要在确认数据可丢弃后手动重建，并重新初始化管理员；不要对需要保留数据的数据库直接执行此操作，也不要用 Flyway repair 或关闭校验绕过差异。迁移文件合并／移动后用 `mvn clean package` 清除旧构建资源。正式发布后冻结基线，再使用版本化增量迁移。

### 初始化管理员与密码登录

启动前显式初始化首位超管（交互隐藏输入密码，不能传 `--password`）：

```sh
java -jar freesia-server/target/freesia-admin.jar bootstrap-admin --username=your-admin
```

非交互部署添加 `--password-file=/run/secrets/freesia-admin-password`，使用受保护的 Secret 文件；初始化成功后移除初始化秘密。重复／并发执行不会重置账号。没有默认账号、密码或管理员 Token。

密码登录、当前账号／数据库菜单和当前端退出已接通，登录结果同步审计。Access 默认 30 分钟、固定会话期限 14 天；本阶段签发初始 Refresh，但刷新轮换与浏览器续接属于独立能力。Argon2id 成本和并发见 `freesia.iam.password`；登录失败默认按账号＋IP 限制。仅在有明确代理时配置 `freesia.web.trusted-proxies`，默认忽略转发头。

Valkey 启动不可达会拒绝启动；运行故障时存活探针保持可用，就绪探针及依赖请求返回 503，恢复后自动可用。探针为 `/actuator/health/liveness`、`/actuator/health/readiness`。

离线 IPv4／IPv6 归属地沿用旧版两份 xdb；来源、校验值和权利范围说明随资源分发于 `ip2region/README.md`，第三方许可证位于 `META-INF/licenses/`。可在 `freesia.iam.ip-location` 配置受信 `file:` 路径替换；没有后台下载或自动更新。

项目正在开发中。`/api/product` 提供产品信息；账号／角色／菜单编辑及其他管理能力逐步接通。工作台等既有演示保留，不代表真实指标。

## 验证

```sh
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
```

后端测试显式使用 `test` 环境及临时 PostgreSQL 和 Valkey 容器，不连接本地开发库。前端检查包含 ESLint、TypeScript 和生产构建。

## 文档

- [前端开发](./freesia-web/README.zh-CN.md)
- [开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md)
- [提交规范](./specs/COMMIT_SPEC.zh-CN.md)
- [PR 流程](./specs/PR_SPEC.zh-CN.md)
- [贡献指南](./CONTRIBUTING.zh-CN.md)

## 许可证

见[后端 LICENSE](./freesia-admin/LICENSE)和[前端 LICENSE](./freesia-web/LICENSE)。前端构建产物包含 `LICENSE.txt`。
