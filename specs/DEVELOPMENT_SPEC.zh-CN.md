# Freesia 开发规范

[English](./DEVELOPMENT_SPEC.md) | 简体中文

本规范是 Freesia 的开发约定。项目按“先共同底座、再业务能力”的顺序建设。

## 1. 产品与仓库

- 产品为 Freesia，管理端为 Freesia Admin。
- `freesia-admin/`：Java 25、Spring Boot 4.1 的 Maven reactor；BOM、framework 技术底座、server 业务应用和按需 extensions。
- `freesia-web/`：Vue 3 / TypeScript / Vite / Element Plus / Pinia 前端。
- `infra/`：本地 PostgreSQL / Valkey 编排；业务 schema 由 Flyway 管理。
- `specs/`：纳入版本管理的开发、提交、架构和产品约定；任务范围与验证结果记录在 PR 或关联 GitHub issue 中。`docs/` 仅存放被忽略的本地 skill 工作材料。
- 根目录负责共同协作；专属构建与检查配置放对应子包。

## 2. 后端组织

`freesia-server` 的启动根包为 `com.freesia.admin`，只放启动类。直接子包是五个业务模块：`iam`、`file`、`notification`、`audit`、`dashboard`。登录开关、验证策略和 TOTP 归入 `iam`；模块根包只放 `package-info.java`，公开契约/DTO/事件放 `api/` 并声明 `@NamedInterface("api")`，实现放 `internal/<capability>/`。模块的表和 SQL 由模块拥有。

framework 包为 `com.freesia.common` / `com.freesia.framework.*`，负责技术装配，不引用 `com.freesia.admin..`、不拥有业务表或 SQL。三个 starter 之间没有生产依赖，本工程内只依赖 common；测试可组合 starter。扩展提供连接与资源限制，业务 QueryPort 和 SQL 留在 server 所属模块。

按用例命名，例如 `ChangeRolePermissions`、`RoleQueries`；请求、业务规则和持久化操作靠近放置。Controller 放在所属能力内。禁止根级 `controller/service/impl/mapper` 大杂烩，不要求每个类都有接口或 `Impl`，不为普通 CRUD 创建无意义领域对象。

跨模块仅使用命名 API，依赖写入 `package-info.java` 的 `allowedDependencies`，例如 `"iam :: api"`。出现真实调用后才声明依赖，跨模块不引用内部实体/Mapper，不直接操作其他模块表。通知的调用方传入收件语言，通知不反查账号。`ApplicationModules.of(...).verify()` 和 framework 不依赖业务包的结构检查必过。

## 3. 读写与事务

- JdbcClient 负责列表、详情、聚合以及命令校验查询，直接映射明确 DTO。
- MyBatis-Flex 负责写入；共用一个 DataSource 与 Spring JDBC 事务管理器。项目显式共享一个 FlexDataSource Bean，关闭 Flex 独立事务装配并使用 SpringManagedTransactionFactory，确保 JdbcClient 与 Flex 共享事务。
- 写用例在明确的应用入口使用事务；不用 Flex 私有事务、Flex 动态多数据源或跨库事务。只读副本路由随审计查询落地，默认关闭，只允许在连接池层实现；授权、敏感校验和写后读始终访问主库。
- SQL 使用绑定参数，排序/列名必须来自服务端白名单；原生查询显式包含状态/逻辑删除条件。
- 业务错误由明确异常表达，HTTP 状态与业务码保持一致；不将异常吞掉返回成功。
- 错误消息按受支持的 `Accept-Language` 解析，默认简体中文。设置 `spring.messages.fallback-to-system-locale=false`，应用翻译缺失时使用默认资源或错误文案，避免回退到主机语言。
- 外部邮件、对象存储操作不属于数据库原子事务；明确失败补偿，禁止长事务等待外部网络。

## 4. 数据库

只维护 PostgreSQL 方言；默认 18，17 的兼容由需要时的验证决定。版本化 SQL 放 `freesia-admin/freesia-server/src/main/resources/db/migration/`，沿用单一线性历史，已执行的迁移文件不可修改；新增变更使用新的版本。

Flyway 历史表通过 `spring.flyway.default-schema=public` 固定位置，业务 SQL 显式指定模块表所在的 `freesia` schema，避免账号同名 schema 影响 `search_path` 后重复迁移。

账号/角色/菜单/配置先普通表。BIGINT 主键默认 identity/sequence，DTO 的 ID 使用包装类型 `Long`，JSON 统一输出字符串；计数使用基本类型 `long` / `int`，保持数字。时间使用数据库 `timestamptz` 和 Java `Instant`，JSON 输出 ISO-8601 UTC；前端决定显示时区，邮件和导出显式传入时区。唯一/非空/必要外键约束由数据库兜底。

审计大表有真实时间查询/保留周期后才分区，唯一键包含分区键；维护分区预建、迟到数据与封存策略。partial index、GIN、BRIN 根据实际 SQL/执行计划选择。BM25 是可选扩展，不作为原生 PG 保证。大日志采用游标与时间边界，普通后台页面可用页码分页。

## 5. 认证与 RBAC

不引入租户。目标为账号→角色→菜单/按钮权限，内置角色为 `R_SUPER/R_ADMIN/R_USER`。角色等级只约束管理操作，不隐式产生权限或继承。权限码统一列清单，同步后端、菜单种子、前端按钮与路由。

后端始终执行鉴权，菜单隐藏不代表安全。禁用角色/账号、撤权、越级写入和至少一名启用超管属于必须验证的规则。授权默认直接查数据库；当前通过 `AuthorizationSource` 读取权限，没有来源实现时权限检查拒绝访问。随 IAM 增加 `none|versioned` 缓存，默认 `none`；通过验收测试验证版本核验、并发失效和旧值回填，不能仅靠 TTL 或广播，授权管理写操作始终直接查库。

每个后端能力同时实现对应的前端登录、菜单和管理流程。不添加假登录、假权限或默认管理员令牌。

## 6. 前端

目录职责为 `api`、`views`、`components`、`hooks`、`router`、`store`、`utils`、`types`、`locales`、`config`。业务页面按能力放入 views，避免在模板组件中放具体业务规则。

产品信息集中在 `freesia-web/src/config/product.ts`；名称、标志和链接从该处读取，未配置的外部链接隐藏。静态 HTML 标题与 favicon 同步维护。组件和样式使用 Freesia 前缀，持久化键使用 Freesia Admin 命名空间。响应使用 `code/msg/data`，页码与游标分页分别为 `Api.Common.PaginatedResponse` / `Api.Common.CursorResponse`。

业务页面涵盖认证、账号、角色、菜单、文件、邮件、设置、审计和工作台。通用主题、布局、图表、表单、媒体组件、hooks、指令和工具放入相应公共目录。各能力内同步前后端契约。构建、Lint、格式和提交工具位于前端包内，不纳入个人配置与失效脚本。

请求错误分别保留 HTTP 状态、业务码、服务端 `msg` 和 Trace ID。会话和动态菜单注册使用后端认证与权限，公共页面使用静态路由。前端分发产物包含对应 LICENSE 为 `LICENSE.txt`。

TypeScript 开启 strict；新代码避免 any，边界未知值使用 unknown 后校验。遵循 Prettier/ESLint/Stylelint 规则，使用类型化 props/emits，异步操作表达 loading/错误/空状态。前端权限只控制体验，后端判定为准。

## 7. 依赖与可选设施

Java 依赖由 `freesia-dependencies` 导入 Boot/Modulith/Sa-Token BOM 并锁定少量显式版本，父工程继承该 BOM、管理插件及 `release=25` / `-parameters`；禁止混入旧 Boot BOM、Boot 3 和 MyBatis-Plus。前端提交 lockfile，使用冻结锁安装。Caffeine 用于有界本地缓存；Valkey 用于会话和共享状态，二者不自动组成二级缓存。

DuckDB/ClickHouse 当前仅在 BOM 锁版本，不进入默认依赖、连接池或任务。需求成立后按业务查询接口接入；DuckDB 通过 optional 依赖、显式 profile 和条件装配启用并限制线程/内存/临时目录，关闭时没有外部启动依赖；不提供用户任意 SQL。S3 冷归档先验证文件/manifest/时间边界再移除在线数据，实时分析单独定义同步与数据新鲜度。

## 8. 能力实现

先通过底座验证，再在所属模块中实现一个完整能力。每次记录前后端契约、依赖和验收条件。文件存储、邮件和 TOTP 需要明确行为、配置、接口、数据、扩展点和错误码，并在能力 PR 中提供验收证据。

产品信息、标志、邮件主题、模板、OpenAPI、日志、缓存键、包名、数据库/容器/卷名和文档链接统一使用 Freesia。必要的第三方许可证与版权声明随对应组件提供。

## 9. 验证与协作

仓库统一通过任务分支和 PR 开发，维护者可使用 Merge commit、Squash 或 Rebase 手动合并，详见 [PR_SPEC.zh-CN.md](./PR_SPEC.zh-CN.md)。agent 完成实现、验证及获准发布的 PR 后交维护者；不直接提交/push main、不合并或自动合并。用户当前“不提交”“不 push”等限制优先。

后端在 `freesia-admin/` 执行 `mvn test`，涉及 SQL/事务使用真实 PostgreSQL 测试；模块改动包含结构验证，公开边界复杂时增加模块集成测试。打包使用 `mvn package`，启动入口为 `freesia-server/target/freesia-admin.jar`，开发运行命令见根 README。前端在 `freesia-web/` 执行 `pnpm lint` 和 `pnpm build`，界面变化验证布局及关键交互。不为简单可逆改动增加复刻实现的测试。

测试必须能捕获实际风险：事务回滚、权限边界、分页、分区裁剪或跨模块协作。构建产物、依赖、日志、凭据和临时参考归档不提交。根文档中英文同步；明确区分已实现、目标和待验证。

提交遵循 [COMMIT_SPEC.zh-CN.md](./COMMIT_SPEC.zh-CN.md) 中的 Conventional Commits 规则。
