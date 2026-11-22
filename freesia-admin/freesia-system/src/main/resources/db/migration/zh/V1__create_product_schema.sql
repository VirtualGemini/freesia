-- 未正式发布的开发基线：每种语言只维护一份 V1，不按开发步骤新增迁移版本。
-- 仅用于空库初始化；中文/英文脚本通过 FREESIA_DB_LANGUAGE=zh/en 二选一，禁止同时执行。
-- 已初始化数据库不能靠切换脚本翻译数据；不导入旧业务数据，不种子账号或凭据。
CREATE SCHEMA IF NOT EXISTS freesia;
COMMENT ON SCHEMA freesia IS 'Freesia 业务表所在 schema；Flyway 历史表固定保存在 public。';

-- 身份与访问管理：账号、资料、角色、菜单及认证策略均由 IAM 拥有。
-- 账号与密码凭据；不预置管理员，首位超管由 bootstrap-admin 显式创建。
CREATE TABLE freesia.sys_account (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    username varchar(50) NOT NULL,
    password_hash text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamptz,
    must_change_password boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (username = btrim(username) AND length(username) BETWEEN 3 AND 50),
    CHECK (password_hash LIKE '$argon2id$%')
);
COMMENT ON TABLE freesia.sys_account IS '账号与密码凭据；不预置管理员，首位超管由 bootstrap-admin 显式创建。';
COMMENT ON COLUMN freesia.sys_account.id IS '账号主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_account.username IS '登录名，必须无首尾空白且为 3–50 字符；未删除账号内唯一。';
COMMENT ON COLUMN freesia.sys_account.password_hash IS 'Argon2id PHC 密码摘要，包含计算参数；禁止存储明文密码。';
COMMENT ON COLUMN freesia.sys_account.enabled IS '账号是否启用；禁用后拒绝登录和鉴权。';
COMMENT ON COLUMN freesia.sys_account.deleted IS '逻辑删除标记；登录、授权及用户名唯一性检查排除已删除账号。';
COMMENT ON COLUMN freesia.sys_account.deleted_at IS '逻辑删除时间，可空；由删除用例维护。';
COMMENT ON COLUMN freesia.sys_account.must_change_password IS '是否要求修改密码的状态标记；实际流程由认证用例处理。';
COMMENT ON COLUMN freesia.sys_account.created_at IS '账号创建时间，带时区；默认当前事务时间。';
COMMENT ON COLUMN freesia.sys_account.updated_at IS '账号最近更新时间，带时区；后续更新由写入用例维护，不是自动触发器。';
CREATE UNIQUE INDEX uk_account_username_active ON freesia.sys_account (username) WHERE NOT deleted;
COMMENT ON INDEX freesia.uk_account_username_active IS '仅对未删除账号保证登录名唯一；允许删除后重新使用名称。';

-- 账号一对一个人资料；与登录凭据分离，语言用于账号偏好。
CREATE TABLE freesia.sys_profile (
    account_id uuid PRIMARY KEY REFERENCES freesia.sys_account(id),
    nickname varchar(100),
    email varchar(254),
    phone varchar(32),
    language varchar(8) NOT NULL DEFAULT 'zh' CHECK (language IN ('zh', 'en')),
    time_zone varchar(80)
);
COMMENT ON TABLE freesia.sys_profile IS '账号一对一个人资料；与登录凭据分离，语言用于账号偏好。';
COMMENT ON COLUMN freesia.sys_profile.account_id IS '所属账号主键，同时为资料主键及 sys_account 外键。';
COMMENT ON COLUMN freesia.sys_profile.nickname IS '可空的用户昵称，保留用户原文。';
COMMENT ON COLUMN freesia.sys_profile.email IS '可空的个人资料联系邮箱，不代表已验证的安全邮箱。';
COMMENT ON COLUMN freesia.sys_profile.phone IS '可空的个人资料联系电话。';
COMMENT ON COLUMN freesia.sys_profile.language IS '账号偏好语言，仅 zh/en；默认与所选初始化脚本一致，不改变 HTTP 语言协商。';
COMMENT ON COLUMN freesia.sys_profile.time_zone IS '可空的账号显示时区；不改变数据库和 API 的 UTC 时间契约。';

-- 角色定义；等级只约束管理范围，不隐式继承或授予权限。
CREATE TABLE freesia.sys_role (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    role_code varchar(80) NOT NULL,
    role_name varchar(100) NOT NULL,
    built_in boolean NOT NULL DEFAULT false,
    level integer NOT NULL CHECK (level >= 0),
    enabled boolean NOT NULL DEFAULT true,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE freesia.sys_role IS '角色定义；等级只约束管理范围，不隐式继承或授予权限。';
COMMENT ON COLUMN freesia.sys_role.id IS '角色主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_role.role_code IS '稳定角色编码；内置 R_SUPER/R_ADMIN/R_USER，不随语言改变。';
COMMENT ON COLUMN freesia.sys_role.role_name IS '角色显示名称；内置种子文案随初始化语言选择，业务自定义名称保留原文。';
COMMENT ON COLUMN freesia.sys_role.built_in IS '是否为产品内置角色。';
COMMENT ON COLUMN freesia.sys_role.level IS '非负管理等级；内置超管/管理员/用户分别为 3/2/1，不是权限绕过。';
COMMENT ON COLUMN freesia.sys_role.enabled IS '角色是否启用；禁用角色不参与授权。';
COMMENT ON COLUMN freesia.sys_role.deleted IS '逻辑删除标记；已删除角色不参与授权和有效编码唯一性。';
COMMENT ON COLUMN freesia.sys_role.created_at IS '角色创建时间，带时区；默认当前事务时间。';
COMMENT ON COLUMN freesia.sys_role.updated_at IS '角色最近更新时间，带时区；后续更新由写入用例维护。';
CREATE UNIQUE INDEX uk_role_code_active ON freesia.sys_role(role_code) WHERE NOT deleted;
COMMENT ON INDEX freesia.uk_role_code_active IS '仅对未删除角色保证稳定编码唯一。';
-- 内置角色只提供身份与管理等级；实际权限由关联表明确授予。
INSERT INTO freesia.sys_role(role_code, role_name, built_in, level) VALUES
    ('R_SUPER', '超级管理员', true, 3),
    ('R_ADMIN', '管理员', true, 2),
    ('R_USER', '用户', true, 1);

-- 账号与角色关联；复合主键防止同一角色重复分配。
CREATE TABLE freesia.sys_account_role (
    account_id uuid NOT NULL REFERENCES freesia.sys_account(id),
    role_id uuid NOT NULL REFERENCES freesia.sys_role(id),
    PRIMARY KEY (account_id, role_id)
);
COMMENT ON TABLE freesia.sys_account_role IS '账号与角色关联；复合主键防止同一角色重复分配。';
COMMENT ON COLUMN freesia.sys_account_role.account_id IS '账号主键，引用 sys_account。';
COMMENT ON COLUMN freesia.sys_account_role.role_id IS '角色主键，引用 sys_role。';

-- 菜单与按钮权限定义；菜单隐藏只控制展示，不能代替后端鉴权。
CREATE TABLE freesia.sys_menu (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    parent_id uuid REFERENCES freesia.sys_menu(id),
    name varchar(100) NOT NULL,
    menu_type varchar(8) NOT NULL CHECK (menu_type IN ('menu', 'button')),
    title varchar(120) NOT NULL,
    path varchar(255),
    component varchar(255),
    redirect varchar(255),
    icon varchar(100),
    auth_mark varchar(120),
    sort integer NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT true,
    deleted boolean NOT NULL DEFAULT false,
    keep_alive boolean NOT NULL DEFAULT false,
    is_hide boolean NOT NULL DEFAULT false,
    is_hide_tab boolean NOT NULL DEFAULT false,
    link varchar(2048),
    is_iframe boolean NOT NULL DEFAULT false,
    show_badge boolean NOT NULL DEFAULT false,
    show_text_badge varchar(80),
    fixed_tab boolean NOT NULL DEFAULT false,
    active_path varchar(255),
    is_full_page boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (id IS DISTINCT FROM parent_id),
    CHECK (auth_mark IS NULL OR length(btrim(auth_mark)) > 0),
    CHECK (NOT is_iframe OR link IS NOT NULL)
);
COMMENT ON TABLE freesia.sys_menu IS '菜单与按钮权限定义；菜单隐藏只控制展示，不能代替后端鉴权。';
COMMENT ON COLUMN freesia.sys_menu.id IS '菜单或按钮主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_menu.parent_id IS '父菜单主键；空值表示根节点，不能引用自身。';
COMMENT ON COLUMN freesia.sys_menu.name IS '稳定前端路由/节点名称，未删除节点内唯一；不是本地化显示标题。';
COMMENT ON COLUMN freesia.sys_menu.menu_type IS '节点类型：menu 为菜单，button 为按钮权限。';
COMMENT ON COLUMN freesia.sys_menu.title IS '菜单或按钮显示标题；种子随初始化语言选择，不随请求语言动态翻译。';
COMMENT ON COLUMN freesia.sys_menu.path IS '可空的前端路由路径；子路由可使用相对路径。';
COMMENT ON COLUMN freesia.sys_menu.component IS '可空的前端组件定位符，必须与现有路由组件约定一致。';
COMMENT ON COLUMN freesia.sys_menu.redirect IS '可空的前端路由重定向目标。';
COMMENT ON COLUMN freesia.sys_menu.icon IS '可空的前端图标标识。';
COMMENT ON COLUMN freesia.sys_menu.auth_mark IS '可空的稳定权限码；非空时不得为空白，未删除节点内唯一，供后端授权使用。';
COMMENT ON COLUMN freesia.sys_menu.sort IS '排序值；查询按此值及 UUID 排序，数值越小越靠前。';
COMMENT ON COLUMN freesia.sys_menu.enabled IS '节点是否启用；禁用节点不参与导航和授权。';
COMMENT ON COLUMN freesia.sys_menu.deleted IS '逻辑删除标记；已删除节点不参与导航和授权。';
COMMENT ON COLUMN freesia.sys_menu.keep_alive IS '前端页面组件是否缓存。';
COMMENT ON COLUMN freesia.sys_menu.is_hide IS '是否在菜单中隐藏；不影响后端鉴权要求。';
COMMENT ON COLUMN freesia.sys_menu.is_hide_tab IS '是否在前端标签栏中隐藏。';
COMMENT ON COLUMN freesia.sys_menu.link IS '可空的外部链接；内嵌页面必须提供。';
COMMENT ON COLUMN freesia.sys_menu.is_iframe IS '是否以内嵌 iframe 展示链接；启用时 link 不能为空。';
COMMENT ON COLUMN freesia.sys_menu.show_badge IS '是否显示菜单徽标。';
COMMENT ON COLUMN freesia.sys_menu.show_text_badge IS '可空的菜单文本徽标内容。';
COMMENT ON COLUMN freesia.sys_menu.fixed_tab IS '是否固定前端标签。';
COMMENT ON COLUMN freesia.sys_menu.active_path IS '可空的关联高亮菜单路径。';
COMMENT ON COLUMN freesia.sys_menu.is_full_page IS '是否以全屏页面展示。';
COMMENT ON COLUMN freesia.sys_menu.created_at IS '节点创建时间，带时区；默认当前事务时间。';
COMMENT ON COLUMN freesia.sys_menu.updated_at IS '节点最近更新时间，带时区；后续更新由写入用例维护。';
CREATE UNIQUE INDEX uk_menu_name_active ON freesia.sys_menu(name) WHERE NOT deleted;
COMMENT ON INDEX freesia.uk_menu_name_active IS '仅对未删除节点保证路由/节点名称唯一。';
CREATE UNIQUE INDEX uk_menu_auth_active ON freesia.sys_menu(auth_mark) WHERE NOT deleted AND auth_mark IS NOT NULL;
COMMENT ON INDEX freesia.uk_menu_auth_active IS '仅对未删除且配置权限码的节点保证权限码唯一。';

-- 角色与菜单/按钮授权关联；授权结果始终结合账号、角色及节点状态判断。
CREATE TABLE freesia.sys_role_menu_permission (
    role_id uuid NOT NULL REFERENCES freesia.sys_role(id),
    menu_id uuid NOT NULL REFERENCES freesia.sys_menu(id),
    PRIMARY KEY (role_id, menu_id)
);
COMMENT ON TABLE freesia.sys_role_menu_permission IS '角色与菜单/按钮授权关联；授权结果始终结合账号、角色及节点状态判断。';
COMMENT ON COLUMN freesia.sys_role_menu_permission.role_id IS '被授予权限的角色主键，引用 sys_role。';
COMMENT ON COLUMN freesia.sys_role_menu_permission.menu_id IS '被授予的菜单或按钮主键，引用 sys_menu。';

-- 全局认证与会话策略；当前仅支持一条 global 配置。
CREATE TABLE freesia.sys_access_control (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    scope_key varchar(16) NOT NULL UNIQUE CHECK (scope_key = 'global'),
    general_register_enabled boolean NOT NULL DEFAULT false,
    forgot_password_enabled boolean NOT NULL DEFAULT true,
    access_seconds integer NOT NULL DEFAULT 1800 CHECK (access_seconds > 0),
    refresh_seconds integer NOT NULL DEFAULT 1209600 CHECK (refresh_seconds >= access_seconds)
);
COMMENT ON TABLE freesia.sys_access_control IS '全局认证与会话策略；当前仅支持一条 global 配置。';
COMMENT ON COLUMN freesia.sys_access_control.id IS '策略主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_access_control.scope_key IS '策略范围固定为 global，唯一约束保证只有一条全局配置。';
COMMENT ON COLUMN freesia.sys_access_control.general_register_enabled IS '普通注册开关；开启标记不代表尚未实现的注册流程可用。';
COMMENT ON COLUMN freesia.sys_access_control.forgot_password_enabled IS '忘记密码开关；开启标记不代表尚未实现的找回流程可用。';
COMMENT ON COLUMN freesia.sys_access_control.access_seconds IS 'Access 凭据有效秒数，必须大于 0；默认 1800 秒（30 分钟）。';
COMMENT ON COLUMN freesia.sys_access_control.refresh_seconds IS '自登录开始的会话绝对期限秒数，至少等于 Access 有效期；默认 1209600 秒（14 天）。';
-- 初始化一条全局策略：Access 30 分钟，会话绝对期限 14 天。
INSERT INTO freesia.sys_access_control(scope_key) VALUES ('global');

-- 按认证场景保存验证失败阈值；原子计数与暂停状态存于 Valkey。
CREATE TABLE freesia.sys_verification_policy (
    scene varchar(32) PRIMARY KEY CHECK (scene IN ('password', 'login-mfa')),
    max_failures integer NOT NULL DEFAULT 5 CHECK (max_failures BETWEEN 1 AND 100),
    window_seconds integer NOT NULL DEFAULT 900 CHECK (window_seconds BETWEEN 1 AND 86400),
    pause_seconds integer NOT NULL DEFAULT 900 CHECK (pause_seconds BETWEEN 1 AND 86400)
);
COMMENT ON TABLE freesia.sys_verification_policy IS '按认证场景保存验证失败阈值；原子计数与暂停状态存于 Valkey。';
COMMENT ON COLUMN freesia.sys_verification_policy.scene IS '认证场景主键：password 为密码，login-mfa 为登录二次验证。';
COMMENT ON COLUMN freesia.sys_verification_policy.max_failures IS '同一账号与来源 IP 在窗口内允许的失败阈值，1–100；默认 5 次。';
COMMENT ON COLUMN freesia.sys_verification_policy.window_seconds IS '失败计数窗口秒数，1–86400；默认 900 秒（15 分钟）。';
COMMENT ON COLUMN freesia.sys_verification_policy.pause_seconds IS '达到阈值后的暂停秒数，1–86400；默认 900 秒（15 分钟）。';
-- 按密码/二次验证场景预置失败策略，不代表 MFA 流程已实现。
INSERT INTO freesia.sys_verification_policy(scene) VALUES ('password'), ('login-mfa');

-- 首位超管的一次性初始化标记；命令通过行锁保证并发执行只能完成一次。
CREATE TABLE freesia.sys_bootstrap (
    purpose varchar(32) PRIMARY KEY CHECK (purpose = 'first-super'),
    account_id uuid REFERENCES freesia.sys_account(id),
    completed_at timestamptz,
    CHECK ((account_id IS NULL) = (completed_at IS NULL))
);
COMMENT ON TABLE freesia.sys_bootstrap IS '首位超管的一次性初始化标记；命令通过行锁保证并发执行只能完成一次。';
COMMENT ON COLUMN freesia.sys_bootstrap.purpose IS '初始化用途主键，固定为 first-super。';
COMMENT ON COLUMN freesia.sys_bootstrap.account_id IS '初始化成功的超管账号主键；尚未完成时为空，引用 sys_account。';
COMMENT ON COLUMN freesia.sys_bootstrap.completed_at IS '初始化完成时间，带时区；与 account_id 同时为空或同时有值；命令拒绝再次初始化已完成的记录。';
-- 仅预置未完成标记；账号和密码必须通过服务器 bootstrap-admin 命令创建。
INSERT INTO freesia.sys_bootstrap(purpose) VALUES ('first-super');

-- 最小产品导航与权限种子；只翻译显示文案，路由、权限码和角色编码保持稳定。
INSERT INTO freesia.sys_menu(name, menu_type, title, path, component, icon, sort) VALUES
    ('Dashboard', 'menu', '仪表盘', '/dashboard', '/index/index', 'ri:pie-chart-line', 10);
INSERT INTO freesia.sys_menu(parent_id, name, menu_type, title, path, component, fixed_tab, sort)
SELECT id, 'Console', 'menu', '工作台', 'console', '/dashboard/console', true, 10
FROM freesia.sys_menu WHERE name='Dashboard';
INSERT INTO freesia.sys_menu(name, menu_type, title, path, component, auth_mark, sort) VALUES
    ('LoginLog', 'menu', '登录日志', '/log/login', '/system/log/login', 'system:log:login:query', 90);
INSERT INTO freesia.sys_menu(name, menu_type, title, auth_mark, is_hide, sort) VALUES
    ('AccountRead', 'button', '账号读取', 'system:account:query', true, 100),
    ('RoleRead', 'button', '角色读取', 'system:role:query', true, 110),
    ('MenuRead', 'button', '菜单读取', 'system:menu:query', true, 120),
    ('VerificationRead', 'button', '验证设置读取', 'system:verification:query', true, 130);
INSERT INTO freesia.sys_role_menu_permission(role_id, menu_id)
SELECT role.id, menu.id FROM freesia.sys_role role CROSS JOIN freesia.sys_menu menu
WHERE role.role_code='R_SUPER';
INSERT INTO freesia.sys_role_menu_permission(role_id, menu_id)
SELECT role.id, menu.id FROM freesia.sys_role role CROSS JOIN freesia.sys_menu menu
WHERE role.role_code IN ('R_ADMIN','R_USER') AND menu.name IN ('Dashboard','Console');

-- 审计模块拥有独立快照；不建立跨模块外键，不保留任何登录凭据。
-- 登录与退出审计快照；不关联账号外键，不保存密码或 Access/Refresh 凭据。
CREATE TABLE freesia.sys_login_log (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    account_id uuid,
    username varchar(50),
    session_id varchar(64),
    event_type varchar(16) NOT NULL CHECK (event_type IN ('LOGIN','LOGOUT')),
    login_method varchar(16) NOT NULL DEFAULT 'password',
    stage varchar(24) NOT NULL DEFAULT 'password',
    result integer NOT NULL CHECK (result IN (0,1)),
    outcome_code varchar(80) NOT NULL,
    client_ip inet NOT NULL,
    trace_id varchar(64),
    user_agent varchar(512),
    country_name varchar(120),
    province_name varchar(120),
    city_name varchar(120),
    isp varchar(160),
    country_code varchar(8),
    event_time timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE freesia.sys_login_log IS '登录与退出审计快照；不关联账号外键，不保存密码或 Access/Refresh 凭据。';
COMMENT ON COLUMN freesia.sys_login_log.id IS '审计主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_login_log.account_id IS '可空的账号主键快照；未知账号失败可为空，不因账号删除丢失审计。';
COMMENT ON COLUMN freesia.sys_login_log.username IS '可空的登录名快照，最多 50 字符。';
COMMENT ON COLUMN freesia.sys_login_log.session_id IS '可空的会话标识快照，用于关联与退出去重；不是可用于认证的 Token。';
COMMENT ON COLUMN freesia.sys_login_log.event_type IS '事件类型：LOGIN 为登录，LOGOUT 为退出。';
COMMENT ON COLUMN freesia.sys_login_log.login_method IS '认证方式稳定码；当前密码登录记录为 password。';
COMMENT ON COLUMN freesia.sys_login_log.stage IS '认证阶段稳定码；当前密码验证阶段为 password。';
COMMENT ON COLUMN freesia.sys_login_log.result IS '事件结果：1 为成功，0 为失败；语义不随查看者语言改变。';
COMMENT ON COLUMN freesia.sys_login_log.outcome_code IS '稳定的结果原因码，用于排查，不存本地化错误文案。';
COMMENT ON COLUMN freesia.sys_login_log.client_ip IS '统一可信代理规则解析的客户端 IPv4/IPv6 字面量，禁止 DNS 解析。';
COMMENT ON COLUMN freesia.sys_login_log.trace_id IS '可空的请求 Trace ID，关联应用日志。';
COMMENT ON COLUMN freesia.sys_login_log.user_agent IS '可空的客户端 User-Agent 快照，最多 512 字符。';
COMMENT ON COLUMN freesia.sys_login_log.country_name IS '可空的离线归属地国家名称，仅为历史数据排查信息。';
COMMENT ON COLUMN freesia.sys_login_log.province_name IS '可空的离线归属地省/州名称。';
COMMENT ON COLUMN freesia.sys_login_log.city_name IS '可空的离线归属地城市名称。';
COMMENT ON COLUMN freesia.sys_login_log.isp IS '可空的离线归属地网络运营商信息。';
COMMENT ON COLUMN freesia.sys_login_log.country_code IS '可空的离线归属地国家编码。';
COMMENT ON COLUMN freesia.sys_login_log.event_time IS '事件实际发生时间，带时区；与 UUID 共同用于稳定排序。';
COMMENT ON COLUMN freesia.sys_login_log.created_at IS '审计入库时间，带时区；默认当前事务时间。';
CREATE INDEX ix_login_log_time ON freesia.sys_login_log(event_time DESC,id DESC);
COMMENT ON INDEX freesia.ix_login_log_time IS '按事件时间与 UUID 倒序查询审计；排序与数据库 UUID 顺序一致。';
CREATE INDEX ix_login_log_session ON freesia.sys_login_log(session_id,event_time DESC);
COMMENT ON INDEX freesia.ix_login_log_session IS '按会话标识查询事件，并按事件时间倒序读取。';

-- 管理操作审计快照；账号与目标保留标识，不建立跨模块外键。
CREATE TABLE freesia.sys_operation_log (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    actor_id uuid,
    action varchar(100) NOT NULL,
    target_type varchar(80),
    target_id varchar(100),
    result integer NOT NULL CHECK (result IN (0,1)),
    outcome_code varchar(80) NOT NULL,
    trace_id varchar(64),
    occurred_at timestamptz NOT NULL
);
COMMENT ON TABLE freesia.sys_operation_log IS '管理操作审计快照；账号与目标保留标识，不建立跨模块外键。';
COMMENT ON COLUMN freesia.sys_operation_log.id IS '操作审计主键，由 PostgreSQL uuidv7() 生成。';
COMMENT ON COLUMN freesia.sys_operation_log.actor_id IS '可空的操作者账号主键快照。';
COMMENT ON COLUMN freesia.sys_operation_log.action IS '稳定的操作标识，不存本地化动作句子。';
COMMENT ON COLUMN freesia.sys_operation_log.target_type IS '可空的操作目标类型稳定码。';
COMMENT ON COLUMN freesia.sys_operation_log.target_id IS '可空的目标标识快照；实体 UUID 使用字符串。';
COMMENT ON COLUMN freesia.sys_operation_log.result IS '操作结果：1 为成功，0 为失败。';
COMMENT ON COLUMN freesia.sys_operation_log.outcome_code IS '稳定的结果原因码，不存本地化错误文案。';
COMMENT ON COLUMN freesia.sys_operation_log.trace_id IS '可空的请求 Trace ID，关联应用日志。';
COMMENT ON COLUMN freesia.sys_operation_log.occurred_at IS '操作实际发生时间，带时区。';

-- 退出重试只补写同一次撤销事件，不重复记录成功退出。
CREATE UNIQUE INDEX uk_login_log_logout_session ON freesia.sys_login_log(session_id)
WHERE event_type='LOGOUT' AND session_id IS NOT NULL;
COMMENT ON INDEX freesia.uk_login_log_logout_session IS '同一非空 session_id 最多一条 LOGOUT，保证退出重试审计不重复。';
