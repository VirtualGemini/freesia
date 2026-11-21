-- Audit owns its snapshots; no cross-module foreign key or credential is retained.
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
CREATE INDEX ix_login_log_time ON freesia.sys_login_log(event_time DESC,id DESC);
CREATE INDEX ix_login_log_session ON freesia.sys_login_log(session_id,event_time DESC);

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
