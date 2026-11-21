# Freesia

[English](./README.md) | 简体中文

Freesia 是使用 Vue 3、TypeScript 和 Spring Boot 构建的现代化管理系统。

## 本地运行

需要 JDK 25、Maven 3.9+、Node 20.19+、pnpm 11 和 Docker。通过 `mvn --version` 确认 Maven 使用 JDK 25。

启动后端：

```sh
docker compose -f infra/docker-compose.yml up -d
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

PostgreSQL 最低支持版本为 18。实体 ID 契约为数据库生成的 UUIDv7，HTTP 使用 UUID 字符串；计数等非 ID 数值保持数字，不提供旧数据或旧 ID 编码兼容。

后端错误和校验文案按 `Accept-Language`（`zh`／`en`，默认 `zh`）及模块资源文件解析，约定见[开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md#31-后端国际化)。

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

后端测试使用临时 PostgreSQL 和 Valkey 容器。前端检查包含 ESLint、TypeScript 和生产构建。

## 文档

- [前端开发](./freesia-web/README.zh-CN.md)
- [开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md)
- [提交规范](./specs/COMMIT_SPEC.zh-CN.md)
- [PR 流程](./specs/PR_SPEC.zh-CN.md)
- [贡献指南](./CONTRIBUTING.zh-CN.md)

## 许可证

见[后端 LICENSE](./freesia-admin/LICENSE)和[前端 LICENSE](./freesia-web/LICENSE)。前端构建产物包含 `LICENSE.txt`。
