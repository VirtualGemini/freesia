# Freesia

[English](./README.md) | 简体中文

Freesia 是使用 Vue 3、TypeScript 和 Spring Boot 构建的现代化管理系统。

后端中，`freesia-server` 只负责启动和部署配置，业务代码、迁移及资源位于 `freesia-system`；通用技术能力仍在 `freesia-framework`。

## 本地运行

需要 JDK 25、Maven 3.9+、Node 20.19+、pnpm 11 和 Docker。通过 `mvn --version` 确认 Maven 使用 JDK 25。

先准备可访问的 PostgreSQL 18+ 和 Valkey；本地连接使用 `application-dev.yml`，默认端口为 15432、16379，可用 `FREESIA_*` 环境变量覆盖。然后直接启动后端：

```sh
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

### 容器测试与生产配置

本地开发保持上述方式：前后端直接运行，后端默认 `application-dev.yml`。根目录的 `docker-compose.yml` 用于完整容器环境，测试与生产共用这一份文件；测试直接指定 `--env-file docker.env`，生产指定 `--env-file docker.prod.env`，不需要额外的 `.env` 文件：

| 运行方式 | 配置入口 | 后端环境 | 应用与依赖 |
| --- | --- | --- | --- |
| 本地开发／测试 | `application-dev.yml` + 本地进程 | `dev` | 本地前后端 + 自行准备的 PostgreSQL／Valkey |
| 容器测试 | `docker.env` | `test` | 容器前后端 + 独立 PostgreSQL／Valkey |
| 生产部署骨架 | `docker.prod.env` | `prod` | 容器前后端 + 独立 PostgreSQL／Valkey |

在仓库根目录构建和启动测试环境：

```sh
docker compose --env-file docker.env config --quiet
docker compose --env-file docker.env up -d --build --wait
# 显式初始化首位管理员，终端隐藏输入密码；不会随容器启动自动创建账号
docker compose --env-file docker.env run --rm freesia-admin bootstrap-admin --username=your-admin
docker compose --env-file docker.env logs -f freesia-admin
```

浏览器访问 `http://127.0.0.1:18080`；前端 Nginx 同源转发 `/api`，Vue history 路由可直接刷新。后端、数据库和 Valkey 不发布宿主机端口，Actuator 不经前端入口暴露。`up --wait` 会等待依赖和应用就绪，但不会初始化管理员或验证账号密码。

两份环境文件控制项目名、后端 profile、入口、凭据、初始化语言和代理网络；`--env-file` 用于 Compose 变量替换，后端只收到 Compose 明确映射的参数，不会将整份文件作为前端构建参数。测试文件中的口令仅为样例；部署环境变量优先于文件，真实凭据不要提交。

`freesia-test` 和 `freesia-prod` 使用不同容器／网络／卷名，不管理本地开发依赖。停止测试环境用 `docker compose --env-file docker.env down`，数据卷保留；`down -v` 会删除对应环境数据，不要用于需要保留的数据。PostgreSQL 的初始化口令只对空卷生效，修改环境变量不会重置已有账号口令。

代理使用单独网段和固定 Nginx IP，后端只信任该 IP；如网段与主机／VPN 冲突，同时调整环境文件中的 `FREESIA_PROXY_SUBNET` 和 `FREESIA_PROXY_IP`。Nginx 覆盖客户端提供的转发头，不将任意转发链视为可信。当前按直连测试入口解析来源；生产前置 HTTPS 网关时，须另行明确真实来源与代理信任链。

生产配置只提供骨架，当前不执行生产部署。`docker.prod.env` 的数据库和 Valkey 口令留空，部署时通过受保护的环境注入 `FREESIA_DB_PASSWORD`、`FREESIA_REDIS_PASSWORD`。`config` 仅校验配置语法；未提供口令不能初始化空 PostgreSQL 卷，Valkey 也不会以无认证方式启动。生产命令：

```sh
docker compose --env-file docker.prod.env config --quiet
docker compose --env-file docker.prod.env up -d --build --wait
```

默认生产 HTTP 入口仅监听 `127.0.0.1:18081`，供服务器 HTTPS 入口转发；TLS、备份和生产运维配置须在真实部署前完成。不要将未加密的 HTTP 入口直接开放到公网。

PostgreSQL 最低支持版本为 18。实体 ID 契约为数据库生成的 UUIDv7，HTTP 使用 UUID 字符串；计数等非 ID 数值保持数字，不提供旧数据或旧 ID 编码兼容。

后端错误和校验文案按 `Accept-Language`（`zh`／`en`，默认 `zh`）及模块资源文件解析，约定见[开发规范](./specs/DEVELOPMENT_SPEC.zh-CN.md#31-后端国际化)。

### 环境配置与开发数据库

部署配置位于 `freesia-server/src/main/resources/`：`application.yml` 为公共配置，`application-dev.yml`、`application-prod.yml`、`application-test.yml` 分别提供开发、生产、测试环境连接配置。未指定环境时默认 `dev`，连接自行准备的本地依赖。

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

### 组织、账号与角色授权

系统管理提供组织树、账号查询／开户／资料编辑、角色配置和独立组织调动。组织没有启停状态；每个角色配置一套数据权限、可使用菜单和可分配权限，支持六种范围及按具体功能合并。只有一个内置超管角色和专属根，不能再次分配；普通角色不设等级或所属组织。

管理员开户输入非空初始密码，保持原文、不强制首次改密，可暂不分配角色。角色成员只显示账号查询范围内的身份和可见数量。角色删除、账号启停／删除、管理员临时密码、完整审计页面及文件／邮件能力仍独立后续接入，不将演示或尚未接通入口视作可用业务。

### 初始化管理员与密码登录

启动前显式初始化首位超管（交互隐藏输入密码，不能传 `--password`）：

```sh
java -jar freesia-server/target/freesia-admin.jar bootstrap-admin --username=your-admin
```

非交互部署添加 `--password-file=/run/secrets/freesia-admin-password`，使用受保护的 Secret 文件；初始化成功后移除初始化秘密。重复／并发执行不会重置账号。没有默认账号、密码或管理员 Token。

初始化与密码登录只校验密码非空且不全为空白，不限制密码长度或字符组合。交互输入为空时，会明确提示并再次隐藏输入，不限制重试次数；可按 Ctrl+C 取消。非交互 Secret 文件为空时明确报错退出，不等待终端输入。

密码登录、当前账号／数据库菜单和当前端退出已接通，登录结果同步审计。Access 默认 30 分钟、固定会话期限 14 天；Refresh 轮换同时更换两种凭据，不改变 sessionId、不延长绝对截止，旧 Refresh 不可重用。既有 Access 按各自期限过期，会话撤销后全部不可用。Argon2id 成本和并发见 `freesia.iam.password`；登录失败默认按账号＋IP 限制。仅在有明确代理时配置 `freesia.web.trusted-proxies`，默认忽略转发头。

浏览器仅在内存保存 Access；登录和刷新响应只返回 Access 与会话信息，不返回 Refresh 明文。持久化 `freesia_refresh` Cookie 为 host-only、HttpOnly、`SameSite=Strict`、`Path=/api/auth`。生产 HTTPS 保持 `freesia.iam.browser-session.cookie-secure=true`，可用 `FREESIA_REFRESH_COOKIE_SECURE` 覆盖；本地 HTTP 开发／测试显式关闭 Secure。登录、刷新和退出须带 `X-Freesia-CSRF: 1`，不要为这些入口开启跨来源携带凭据访问。

浏览器通过 Cookie 恢复有效会话，再查询账号、菜单和权限。仅明确的 Access 过期响应（`401`／`10002`）允许刷新并续接尚未执行的原请求一次，同页并发共享刷新；跨标签认证变更使用 Web Locks 与临时 BroadcastChannel 消息协调，不持久化凭据。浏览器须支持安全上下文中的 Web Locks（生产 HTTPS，本地可用回环 HTTP）。普通网络错误、503 和刷新响应不明不自动重放业务、不清登录。明确会话失效（`401`／`10003`）才结束对应浏览器会话。退出同步到同浏览器其他标签，不影响其他设备。

刷新／退出通过 `X-Freesia-Session` 选择预期会话，同时须有匹配的 Cookie 或 Access 凭据，sessionId 本身不能鉴权。严格一次性刷新已提交、但替换 Cookie 丢失时，可能需要重新登录。迟到响应仍可能覆盖固定名称 Cookie；预期会话校验防止静默恢复其他账号，不保证消除所有 Cookie 可用性故障。

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
