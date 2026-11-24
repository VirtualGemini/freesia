# Freesia 开发规范

[English](./DEVELOPMENT_SPEC.md) | 简体中文

本规范是 Freesia 的开发约定。项目按“先共同底座、再业务能力”的顺序建设。

## 1. 产品与仓库

- 产品为 Freesia，管理端为 Freesia Admin。
- `freesia-admin/`：Java 25、Spring Boot 4.1 的 Maven reactor；BOM、framework 技术底座、system 业务实现、server 启动装配和按需 extensions。
- `freesia-web/`：Vue 3 / TypeScript / Vite / Element Plus / Pinia 前端。
- `infra/`：本地 PostgreSQL / Valkey 编排；业务 schema 由 Flyway 管理。
- `specs/`：纳入版本管理的开发、提交、架构和产品约定；任务范围与验证结果记录在 PR 或关联 GitHub issue 中。`docs/` 仅存放被忽略的本地 skill 工作材料。
- 根目录负责共同协作；专属构建与检查配置放对应子包。

## 2. 后端组织

`freesia-server` 仅为可执行启动模块：生产代码只保留 `com.freesia.admin.FreesiaAdminApplication`，生产资源只保留 `application.yml` 与 `application-dev.yml`、`application-prod.yml`、`application-test.yml` 部署配置，负责启动、引入业务装配并生成 `freesia-admin.jar`；不放业务 Controller、用例、Mapper、SQL、翻译、离线数据或业务专属依赖。

`application.yml` 维护共同配置，通过 `spring.profiles.default=dev` 默认选择本地开发环境；不得用固定 `spring.profiles.active` 阻止部署选择环境。连接参数放对应环境文件，使用 `SPRING_PROFILES_ACTIVE` 或 `--spring.profiles.active` 显式选择 `prod`／`test`，一次只选择一个部署环境。只有 `dev` 提供本地编排地址与开发口令；`prod`／`test` 必须注入专用数据库连接、凭据和 Valkey 地址，不回退开发库。生产 Valkey 密码必须显式提供，未启用认证时显式设空；自动化测试显式使用 `test` 和临时容器，不依赖本地服务。共享国际化、安全、迁移和探针约定不因环境拆分而弱化。

`freesia-system` 为普通 JAR 业务实现模块，根包为 `com.freesia.system`，由 `FreesiaSystemConfiguration` 装配。五个直接业务子包仍是 `iam`、`file`、`notification`、`audit`、`dashboard`，不因 Maven 拆分改变领域边界。登录开关、验证策略和 TOTP 归入 `iam`；模块根包只放 `package-info.java`，对其他模块或启动模块公开的 Java 契约、DTO 和事件放 `api/` 并声明 `@NamedInterface("api")`，实现放 `internal/<capability>/`。`api/` 不等于 HTTP 接口层，Controller 和仅供本能力使用的请求／响应 DTO 属于内部实现。模块的表、SQL、消息资源、离线数据及对应第三方声明随 system JAR 分发。

依赖方向为 `server → system → framework/common`；system 和 framework 不引用启动模块，system 的集成测试使用自身 test-only 启动装配，不为测试增加反向依赖。启动模块测试验证实际装配和产物归属。

framework 包为 `com.freesia.common` / `com.freesia.framework.*`，负责技术装配，不引用 `com.freesia.system..` 或 `com.freesia.admin..`、不拥有业务表或 SQL。三个 starter 之间没有生产依赖，本工程内只依赖 common；测试可组合 starter。扩展提供连接与资源限制，业务 QueryPort 和 SQL 留在 system 所属模块。

跨模块仅使用命名 API，依赖写入 `package-info.java` 的 `allowedDependencies`，例如 `"iam :: api"`。出现真实调用后才声明依赖，跨模块不引用内部实体/Mapper，不直接操作其他模块表。通知的调用方传入收件语言，通知不反查账号。`ApplicationModules.of(...).verify()` 和 framework 不依赖业务包的结构检查必过。

### 2.1 system 模块的能力与职责分包

整个 system 统一按 **业务模块 → 功能能力 → 职责** 组织，不恢复 `domain/application/infrastructure` 等繁琐 DDD 分层，也不因分包新增 Maven 模块。先确定代码属于哪个能力，再按实际职责拆包，不能把整个业务模块的 Controller、Service 或 Mapper 集中到模块根级目录。

当前实现布局如下；花括号列出同级职责包，不代表每个能力必须具备全部职责：

```text
com.freesia.system/
├── FreesiaSystemConfiguration
├── iam/
│   ├── api/
│   └── internal/
│       ├── account/{controller,dto,entity,mapper,query}
│       ├── auth/{controller,dto,filter,service}
│       ├── authorization/{controller,query}
│       ├── availability/{controller,service}
│       ├── bootstrap/{mapper,service}
│       ├── config/
│       ├── menu/{controller,dto,query}
│       ├── origin/{config,service}
│       ├── password/{config,service}
│       ├── role/{controller,dto,query}
│       └── session/{config,dto,query,service}
├── audit/
│   ├── api/
│   └── internal/
│       ├── config/
│       ├── login/{controller,dto,mapper,query,service}
│       └── operation/{mapper,service}
├── dashboard/
│   ├── api/
│   └── internal/product/{controller,dto}
├── file/{api,internal}
└── notification/{api,internal}
```

| 职责包 | 放置内容 |
| --- | --- |
| `controller` | HTTP 路由、参数校验、请求／响应转换及对应异常出口；不写 SQL。仅由 Controller 使用的 Cookie／HTTP 辅助类可就近保留。 |
| `service` | 具体用例、业务规则、事务入口及能力实现；按用例命名，如 `PasswordLogin`、`InitializeAdministrator`，不强制添加 `Service` 后缀。 |
| `query` | JdbcClient 查询及查询结果组装，如 `AccountQueries`、`RoleQueries`；不混用 Controller 注解或 HTTP 请求对象。 |
| `mapper` | MyBatis-Flex 写入 Mapper，SQL 留在所属能力；初始化标记和初始角色绑定归 `bootstrap`，不塞进通用账号 Mapper。 |
| `entity` | 数据库行映射，如 `AccountRow`；不为了分层包装成无行为的领域对象。 |
| `dto` | 本能力请求、响应及跨职责传递的明确数据结构；不因 Java 类型为 `public` 就移入模块 `api/`。只服务单个类的局部结果／辅助 record 可嵌套保留。 |
| `config` | 本能力的配置属性与 Bean 装配；只有模块级共享装配放 `internal/config`，如模块 Mapper 扫描。 |
| `filter` | 本能力的 Servlet 过滤器，如认证凭证入口保护；不集中到整个 system 的公共业务过滤目录。 |

认证请求与密码失败计数归 `auth`，Access／Refresh 生命周期、会话策略归 `session`，账号查询归 `account`，角色归 `role`，菜单导航归 `menu`，权限判定归 `authorization`；密码哈希、来源 IP／离线归属地和可用性分别归 `password`、`origin`、`availability`。审计按登录、操作等能力拆开，不能把所有审计写入和监听器混在 `login`。

只为已有职责建包，不创建空目录、空 Service、占位 Mapper 或转发层。HTTP 直接调用所属 Query／用例即可，不添加仅转发调用的 Service。尚未实现的 file／notification 保持模块边界，不预建存储、模板等未来能力。

同一模块内的不同能力可直接使用所需内部类型，保持依赖方向清晰；优先 package-private，确需跨包调用或框架要求时才使用 `public`。Java 的 `public` 不解除 Spring Modulith 的 `internal` 限制。禁止全局职责包、强制接口＋`Impl` 配对以及没有真实变化点的 Repository／Manager／Facade。调整包名时同步 import、Mapper 扫描与实际装配，执行 `mvn clean test`，避免旧字节码掩盖漏迁；`ModuleStructureTest` 同时约束能力优先布局、HTTP／查询／持久化职责归属和既有模块依赖。

## 3. 读写与事务

- JdbcClient 负责列表、详情、聚合以及命令校验查询，直接映射明确 DTO。
- MyBatis-Flex 负责写入；共用一个 DataSource 与 Spring JDBC 事务管理器。项目显式共享一个 FlexDataSource Bean，关闭 Flex 独立事务装配并使用 SpringManagedTransactionFactory，确保 JdbcClient 与 Flex 共享事务。data starter 注册原生 UUID 类型处理器，支持数据库生成键回填及 UUID 参数绑定；数据库生成的实体主键使用 `@Id(keyType = KeyType.Auto)`。
- 写用例在明确的应用入口使用事务；不用 Flex 私有事务、Flex 动态多数据源或跨库事务。只读副本路由随审计查询落地，默认关闭，只允许在连接池层实现；授权、敏感校验和写后读始终访问主库。
- SQL 使用绑定参数，排序/列名必须来自服务端白名单；原生查询显式包含状态/逻辑删除条件。
- 业务错误由明确异常表达，HTTP 状态与业务码保持一致；不将异常吞掉返回成功。
- 错误消息按受支持的 `Accept-Language` 解析，默认简体中文。设置 `spring.messages.fallback-to-system-locale=false`，应用翻译缺失时使用默认资源或错误文案，避免回退到主机语言。
- 外部邮件、对象存储操作不属于数据库原子事务；明确失败补偿，禁止长事务等待外部网络。

### 3.1 后端国际化

- 使用 Spring `MessageSource` 与 UTF-8 `.properties` 资源，不另建翻译框架、数据库翻译中心或后台翻译管理。固定系统文案随代码审核和发布；账号昵称、自定义角色名、备注等业务数据保持原文，不自动翻译。
- 对外支持 `zh`（简体中文）和 `en`。HTTP 按 `Accept-Language` 的偏好顺序及权重协商；`zh-CN`、`zh-TW` 等中文标签统一选择现有简体资源，`en-US` 等选择英文资源，不暗示支持繁体等额外译本。缺失或没有支持语言时默认 `zh`，不以 URL `lang`、Cookie、服务器区域或 IP 推断语言。
- 请求使用 Spring 自带的语言上下文，不增加第二套 ThreadLocal、静态 Spring 容器或静态 `MessageUtils`。邮件、导出和后台任务显式接收语言／`Locale`，不从残留请求线程推断收件语言；locale 不改变普通 API 的 UTC 时间、数值类型或字段名。
- `ErrorCode` 明确提供业务码、稳定翻译 key、HTTP 状态及英文诊断兜底。key 使用 `<module>.error.<name>`，例如 `iam.error.invalid-credentials`；不能从 Java 类名／枚举名派生，也不能把中文作为身份。common 保持 JDK-only，只定义契约与携带资源，不引用 Spring。
- 业务抛出错误码＋参数，在 HTTP 响应出口翻译 `msg`；`ApiException.getMessage()` 保持不随请求变化的诊断文本。保留 `code/msg/data`、成功码 200 和既有 `OK`；前端继续展示服务端错误 `msg`，不要求前端再次翻译错误 key。日志／审计以稳定码、动作和必要脱敏参数表达，不保存依赖操作者语言的业务语义。
- 通用错误资源归 common：`i18n/common/messages.properties`（简体默认）与 `messages_en.properties`；共享系统资源为 system 的 `i18n/messages*`，业务资源为所属模块的 `i18n/<module>/messages*`。业务 key 使用 `iam/file/notification/audit/dashboard` 命名空间，不堆进 common；非错误 key 使用 `.validation.`、`.message.`、`.export.` 分类。
- 通过 `spring.messages.basename` 显式注册实际资源路径，多个路径用列表或逗号分隔；每个 basename 必须有默认文件，英文与默认 key 完整对应。starter 自动在应用 basenames 后加入 common 资源，共用一个可注入的 MessageSource；应用路径顺序决定优先级，业务模块之间不得重复定义同一个 key。
- `spring.messages.encoding=UTF-8`，禁止宿主语言回退和返回原始 key（`fallback-to-system-locale=false`、`use-code-as-default-message=false`，默认装配也强制这两项）。按资源顺序解析并使用资源自身的默认译本，仍缺失时使用错误码诊断兜底；不把缺 key 静默视为翻译完整。
- Bean Validation 使用同一个 MessageSource，约束写 `message="{iam.validation.username-required}"`，不在生产注解硬编码文案。错误文案使用 `MessageFormat` 的 `{0}` 参数，带参数的句子中单引号写为 `''`，不能把占位符意外引成字面量；校验资源使用约束属性如 `{min}`、`{max}`、`{value}`。禁止拼接完整句子，参数不得包含密码／Token；未解析校验 key 和绑定内部详情返回本地化通用 400，返回值校验／未知故障返回通用 500。Servlet `/error` 分派同样返回本地化 JSON 信封，不输出 Boot 默认错误对象、HTML 或内部错误详情。
- `MessageCatalogTest` 作为资源契约检查，验证默认／英文文件配对、key 唯一性与命名、非空文案、占位参数和通用错误覆盖；新增业务错误也须验证其 key 存在。公开 HTTP 测试覆盖语言权重／地区标签、缺失翻译、校验／鉴权／框架错误、状态码与 Trace，不能仅断言私有翻译工具。
- 账号语言偏好由 IAM 存 PostgreSQL，framework 不为解析每个请求查询账号。可编辑邮件模板属于 notification 业务内容：调用方传入收件语言，同用途同语言自定义模板不可用时使用同语言内置模板，不与系统消息资源合并；语言选择不能隐式创建跨 Module 依赖。

## 4. 数据库

只维护 PostgreSQL 方言，最低支持版本为 18；不支持 17 及更早版本，不为旧版本提供 UUIDv7 生成回退。SQL 放 `freesia-admin/freesia-system/src/main/resources/db/migration/`，由启动模块从依赖 JAR 的 classpath 加载。

**当前未正式发布的开发阶段每种语言只维护 `V1__create_product_schema.sql` 一份初始化迁移。** 中文版位于 `db/migration/zh/`，英文版位于 `db/migration/en/`，是同一基线的两个互斥选择，不是两个迁移步骤。表、索引、约束和种子每次更新同步修改这两份文件，禁止按开发步骤新增 V2／V3 等 migration SQL；任一语言均须能完整初始化空库。

SQL 使用对应语言的分段说明，以及 PostgreSQL `COMMENT ON SCHEMA/TABLE/COLUMN` 为 schema、每张表、每个字段写入可查询的数据库注释；约束、索引及种子说明业务含义、单位、枚举、可空性和关键默认值，不只复述类型。英文版的注释、角色／菜单／按钮显示文案均为英文，中文版使用中文；账号资料默认语言分别为 `en`／`zh`。两版的结构、约束、权限映射与稳定业务编码保持一致，只允许注释、显示文案及默认资料语言不同，测试以真实 PostgreSQL 检查注释完整性和双版本一致性。

`spring.flyway.locations=classpath:db/migration/${FREESIA_DB_LANGUAGE:zh}` 默认选择中文；初始化前设置 `FREESIA_DB_LANGUAGE=en` 选择英文，也可直接指定单个 `spring.flyway.locations`。不得扫描共同父目录或同时指定两版。保持 `fail-on-missing-locations=true`，语言／路径错误直接失败。启动和 `bootstrap-admin` 必须选择同一版本；已初始化数据库切换语言会校验失败，不能作为在线翻译或重写业务数据的方法。此选择与 `Accept-Language` 的运行时消息协商独立。

不承诺升级此前的临时开发库。已执行旧基线的开发库会触发 Flyway 校验失败，确认数据可丢弃后由开发者手动重建专用数据库，再重新初始化管理员；保留数据的数据库先备份并单独制定迁移方案，禁止自动删库、关闭校验或用 repair 掩盖差异。合并／移动／删除迁移文件后执行 `mvn clean`，避免旧 SQL 残留在构建产物。

进入正式发布与版本迭代后，发布的基线／已执行迁移冻结，后续变更恢复为新增版本的单一线性历史，不修改已发布文件；当前单文件开发规则不能用于改写正式环境历史。

Flyway 历史表通过 `spring.flyway.default-schema=public` 固定位置，业务 SQL 显式指定模块表所在的 `freesia` schema，避免账号同名 schema 影响 `search_path` 后重复迁移。

账号/角色/菜单/配置先普通表。独立实体主键使用 PostgreSQL 原生 `uuid`，默认由数据库 `uuidv7()` 生成，Java 获取生成值并使用 `UUID`；关联表可按模型使用复合键。所有 HTTP 入参与响应中的实体 ID 使用 UUID 字符串，不兼容旧数值 ID、Base62 或 Snowflake，不在应用层另行发号。前端不将实体 ID 解析为数字，也不扫描 JSON 文本修补旧大整数 ID。计数、大小和耗时等非 ID 数值保持 JSON 数字，不因包装类型 `Long` 全局转为字符串。接口已定义的可空字段为空时返回 `null`，仅在契约明确允许时省略；写入中的省略与明确清空由写契约分别定义。时间使用数据库 `timestamptz` 和 Java `Instant`，JSON 输出 ISO-8601 UTC；前端决定显示时区，邮件和导出显式传入时区。唯一/非空/必要外键约束由数据库兜底。

审计大表有真实时间查询/保留周期后才分区，唯一键包含分区键；维护分区预建、迟到数据与封存策略。partial index、GIN、BRIN 根据实际 SQL/执行计划选择。BM25 是可选扩展，不作为原生 PG 保证。大日志采用时间与 UUID ID 组成的稳定游标，按数据库排序进行后续查询；客户端只持有不透明游标，不能用 Java `UUID.compareTo` 的有符号顺序替代 PostgreSQL UUID 排序。普通后台页面可用页码分页。

## 5. 认证与 RBAC

不引入租户。使用账号→角色→菜单／按钮 RBAC；每个角色配置一套数据权限 D、独立菜单使用权限 M 和可分配权限 G。仅内置唯一 `R_SUPER`，无账号／角色等级、角色所属组织或专用子管理员身份。组织仅承载树与数据范围，没有状态或启停功能。

- D 选项固定为仅本人、仅所在组织下级组织、所在组织、所在组织及下级、指定组织、全部数据；相对组织取实际持有者当前所属组织。无组织账号不能绑定三种相对组织角色，清空组织须先处理不兼容绑定。指定组织保存显式选中节点，默认父子联动、可独立选择，半选和未来新增子节点不自动授权。
- 同一功能按授予该功能的有效角色合并范围，不同功能不串范围；M 与 G 分别合并。普通操作按对应 M＋D，不增加同组织写入禁令、目标完整权限包含或部门菜单天花板。G 限制可授出的功能，不要求本人先有对应 M，不增设另一套管理员授权数据范围配置。
- 角色目录／定义仅开放 G 能覆盖完整 M、G 的可操作角色；编辑同时检查原内容和新内容，启停检查当前内容，正式提交复核。角色分配／解除只操作本次差量，保留目标其他不可管理绑定；自绑定按相同规则允许，创建角色不自动绑定。共享角色不要求控制全部持有者。成员只读名单仅返回账号查询范围内身份与可见数量。
- 账号可空地属于一个组织。组织调动使用独立功能权限，以调动前权限检查源账号、目标组织及变化范围；允许合法自我调动，不自动解绑角色。组织改父不追加子树权限天花板。组织存在下级、账号或有效指定组织引用时不可删除。
- 根节点和 `R_SUPER` 绑定仅供初始化唯一超管，不可再次分配；唯一超管不可停用、删除、迁出根或解绑，根／超管角色不可停用删除。根仅超管能改名称备注，身份、编码及父节点固定。普通全部数据不解除超管保护；超管正常资料操作保留。
- 账号创建允许零角色；管理员输入初始密码，仅校验非空且不全为空白，不 trim、不回显、不强制首次改密。已有账号恢复继续使用独立系统临时密码流程，不能借普通资料接口修改凭据、角色、组织或状态。

后端始终执行鉴权，隐藏菜单、前端按钮和恢复标签都不是安全边界。功能码同步后端、种子和前端。权限及敏感校验默认直接查主库；管理写事务锁定权威状态后复核，成功审计同事务，失败回滚后独立记录。`AuthorizationSource` 仅提供技术入口所需功能集合，D／G 的业务规则由 IAM 拥有，framework 不反向引用 system。现有标准 Caffeine 设施不代表授权缓存获批；未来 versioned 缓存须验证版本、并发失效与旧值回填，不能只靠 TTL 或广播。

每个能力同时接通必要页面和公开行为验证；保留原前端架构与演示，不添加假登录、假权限或默认管理员令牌。

### 5.1 密码认证底座

- 首位超管只通过服务器显式 `bootstrap-admin` 命令创建；密码由隐藏输入或 Secret 文件提供，不接受明文命令参数，不输出秘密。数据库锁定一次性初始化标记，重复／并发执行不能重置账号或密码；迁移只创建专属根、唯一内置角色、最小导航和策略，不种子账号。
- 密码固定 Argon2id，自描述 PHC 记录保留验证参数；默认 65536 KiB／3 次／4 lanes，可由 `freesia.iam.password` 调整，计算并发有界。成功登录需要升配时逐项取已有／配置成本的较大值，不因混合调参降低任一已有维度；不接受旧算法。Spring crypto 只用于密码，不引入第二套登录框架。
- PostgreSQL 保存 Access／会话策略，默认 30 分钟／14 天。每次登录有独立稳定 sessionId 和随机 Access／Refresh，绝对截止从登录固定计算；Valkey 仅保存 Refresh 摘要，不建历史会话表。短期 Access 过期与会话撤销分别表达，Refresh 轮换由独立能力实现，初始签发不等于刷新闭环。
- 登录业务码：`10001`／400 为中性密码失败；`10002`／401 为执行前明确 Access 过期；`10003`／401 为会话失效；`10004`／429 为账号＋来源 IP 暂停；`10005`／503 为密码计算繁忙。数据库／Valkey 暂不可用返回通用 503；不能改成 401，也不能因网络故障清除浏览器凭证。
- 相同账号＋可信来源 IP 默认 15 分钟失败 5 次暂停 15 分钟，策略存 PostgreSQL，计数与转换由 Valkey 原子执行。触发前成功清计数；不锁整个账号、不影响其他来源或已有会话。权限、账号和角色状态仍直接查库，角色名不是权限绕过。
- 登录成功审计同步提交后才激活并返回凭证；失败补偿仅撤销本次新会话，不启用 Sa-Token 默认会话数量挤出。主动退出先撤当前 IAM 会话与 Refresh 映射，即使 Sa-Token 清理暂不可用也不能恢复权限；已过期 Access 仅能在绝对截止前用于撤销，不能执行业务。退出审计失败的重试上下文在 Valkey 最多保留 24 小时，只含摘要寻址及必要快照，不恢复凭证；重试审计按 sessionId 去重，不承诺永久队列投递。common 只携带最小审计数据；同步事件由 audit 持久化，不形成 IAM→audit→其他业务环。后续管理成功审计必须加入业务事务，失败审计在原事务回滚后独立记录。
- Valkey 是启动硬依赖；运行中故障进程存活但未就绪，相关请求 503，恢复后探针与请求自动恢复。不回退内存；`liveness` 不包含外部依赖，`readiness` 检查数据库和 Valkey。
- 登录、限流和审计使用同一个字面量 IP 解析器：默认直连，只有 `freesia.web.trusted-proxies` 明确配置的代理才解析转发链，禁止 DNS。离线双栈归属地沿用已确认的历史 xdb，仅供排查；保留来源、校验值和第三方声明，不保证数据时效或实际位置，不把库许可等同所有输入数据的权利证明。Searchers 独占、有界，无结果缓存；缺失／未命中／饱和返回未知并保留 IP。
- 登录页滑块只是交互，不请求后端票据，不显示无效“记住密码”。未接通的注册、邮件或 MFA 不返回假成功；演示工作台和既有前端架构继续保留。

## 6. 前端

目录职责为 `api`、`views`、`components`、`hooks`、`router`、`store`、`utils`、`types`、`locales`、`config`。业务页面按能力放入 views，避免在模板组件中放具体业务规则。

产品信息集中在 `freesia-web/src/config/product.ts`；名称、标志和链接从该处读取，未配置的外部链接隐藏。静态 HTML 标题与 favicon 同步维护。组件和样式使用 Freesia 前缀，持久化键使用 Freesia Admin 命名空间。响应使用 `code/msg/data`，页码与游标分页分别为 `Api.Common.PaginatedResponse` / `Api.Common.CursorResponse`。

业务页面涵盖认证、账号、角色、菜单、文件、邮件、设置、审计和工作台。通用主题、布局、图表、表单、媒体组件、hooks、指令和工具放入相应公共目录。各能力内同步前后端契约。构建、Lint、格式和提交工具位于前端包内，不纳入个人配置与失效脚本。

请求错误分别保留 HTTP 状态、业务码、服务端 `msg` 和 Trace ID。会话和动态菜单注册使用后端认证与权限，公共页面使用静态路由。前端分发产物包含对应 LICENSE 为 `LICENSE.txt`。

TypeScript 开启 strict；新代码避免 any，边界未知值使用 unknown 后校验。遵循 Prettier/ESLint/Stylelint 规则，使用类型化 props/emits，异步操作表达 loading/错误/空状态。前端权限只控制体验，后端判定为准。

## 7. 依赖与可选设施

Java 依赖由 `freesia-dependencies` 导入 Boot/Modulith/Sa-Token BOM 并锁定少量显式版本，父工程继承该 BOM、管理插件及 `release=25` / `-parameters`；禁止混入旧 Boot BOM、Boot 3 和 MyBatis-Plus。前端提交 lockfile，使用冻结锁安装。启动时装配标准 Caffeine 缓存管理器，业务仅在明确选择后接入，不自动缓存查询或权限。Caffeine 用于有界本地缓存；Valkey 用于会话和共享状态，二者不自动组成二级缓存。

DuckDB/ClickHouse 当前仅在 BOM 锁版本，不进入默认依赖、连接池或任务。需求成立后按业务查询接口接入；DuckDB 通过 optional 依赖、显式 profile 和条件装配启用并限制线程/内存/临时目录，关闭时没有外部启动依赖；不提供用户任意 SQL。S3 冷归档先验证文件/manifest/时间边界再移除在线数据，实时分析单独定义同步与数据新鲜度。

## 8. 能力实现

先通过底座验证，再在所属模块中实现一个完整能力。每次记录前后端契约、依赖和验收条件。文件存储、邮件和 TOTP 需要明确行为、配置、接口、数据、扩展点和错误码，并在能力 PR 中提供验收证据。

产品信息、标志、邮件主题、模板、OpenAPI、日志、缓存键、包名、数据库/容器/卷名和文档链接统一使用 Freesia。必要的第三方许可证与版权声明随对应组件提供。

## 9. 验证与协作

仓库统一通过任务分支和 PR 开发，维护者可使用 Merge commit、Squash 或 Rebase 手动合并，详见 [PR_SPEC.zh-CN.md](./PR_SPEC.zh-CN.md)。agent 完成实现、验证及获准发布的 PR 后交维护者；不直接提交/push main、不合并或自动合并。用户当前“不提交”“不 push”等限制优先。

后端在 `freesia-admin/` 执行 `mvn test`，涉及 SQL/事务使用真实 PostgreSQL 测试；模块改动包含结构验证，公开边界复杂时增加模块集成测试。打包使用 `mvn package`，启动入口为 `freesia-server/target/freesia-admin.jar`，开发运行命令见根 README。前端在 `freesia-web/` 执行 `pnpm lint` 和 `pnpm build`，界面变化验证布局及关键交互。不为简单可逆改动增加复刻实现的测试。

测试必须能捕获实际风险：事务回滚、权限边界、分页、分区裁剪或跨模块协作。构建产物、依赖、日志、凭据和临时参考归档不提交。根文档中英文同步；明确区分已实现、目标和待验证。

提交遵循 [COMMIT_SPEC.zh-CN.md](./COMMIT_SPEC.zh-CN.md) 中的 Conventional Commits 规则。
