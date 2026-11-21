-- IAM owns these tables. No account, password or bearer credential is seeded.
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
CREATE UNIQUE INDEX uk_account_username_active ON freesia.sys_account (username) WHERE NOT deleted;

CREATE TABLE freesia.sys_profile (
    account_id uuid PRIMARY KEY REFERENCES freesia.sys_account(id),
    nickname varchar(100),
    email varchar(254),
    phone varchar(32),
    language varchar(8) NOT NULL DEFAULT 'zh' CHECK (language IN ('zh', 'en')),
    time_zone varchar(80)
);

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
CREATE UNIQUE INDEX uk_role_code_active ON freesia.sys_role(role_code) WHERE NOT deleted;
INSERT INTO freesia.sys_role(role_code, role_name, built_in, level) VALUES
    ('R_SUPER', 'Super administrator', true, 3),
    ('R_ADMIN', 'Administrator', true, 2),
    ('R_USER', 'User', true, 1);

CREATE TABLE freesia.sys_account_role (
    account_id uuid NOT NULL REFERENCES freesia.sys_account(id),
    role_id uuid NOT NULL REFERENCES freesia.sys_role(id),
    PRIMARY KEY (account_id, role_id)
);

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
CREATE UNIQUE INDEX uk_menu_name_active ON freesia.sys_menu(name) WHERE NOT deleted;
CREATE UNIQUE INDEX uk_menu_auth_active ON freesia.sys_menu(auth_mark) WHERE NOT deleted AND auth_mark IS NOT NULL;

CREATE TABLE freesia.sys_role_menu_permission (
    role_id uuid NOT NULL REFERENCES freesia.sys_role(id),
    menu_id uuid NOT NULL REFERENCES freesia.sys_menu(id),
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE freesia.sys_access_control (
    id uuid DEFAULT uuidv7() PRIMARY KEY,
    scope_key varchar(16) NOT NULL UNIQUE CHECK (scope_key = 'global'),
    general_register_enabled boolean NOT NULL DEFAULT false,
    forgot_password_enabled boolean NOT NULL DEFAULT true,
    access_seconds integer NOT NULL DEFAULT 1800 CHECK (access_seconds > 0),
    refresh_seconds integer NOT NULL DEFAULT 1209600 CHECK (refresh_seconds >= access_seconds)
);
INSERT INTO freesia.sys_access_control(scope_key) VALUES ('global');

CREATE TABLE freesia.sys_verification_policy (
    scene varchar(32) PRIMARY KEY CHECK (scene IN ('password', 'login-mfa')),
    max_failures integer NOT NULL DEFAULT 5 CHECK (max_failures BETWEEN 1 AND 100),
    window_seconds integer NOT NULL DEFAULT 900 CHECK (window_seconds BETWEEN 1 AND 86400),
    pause_seconds integer NOT NULL DEFAULT 900 CHECK (pause_seconds BETWEEN 1 AND 86400)
);
INSERT INTO freesia.sys_verification_policy(scene) VALUES ('password'), ('login-mfa');

CREATE TABLE freesia.sys_bootstrap (
    purpose varchar(32) PRIMARY KEY CHECK (purpose = 'first-super'),
    account_id uuid REFERENCES freesia.sys_account(id),
    completed_at timestamptz,
    CHECK ((account_id IS NULL) = (completed_at IS NULL))
);
INSERT INTO freesia.sys_bootstrap(purpose) VALUES ('first-super');
