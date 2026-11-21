-- Retried logout persists the same revocation event, not another successful logout.
CREATE UNIQUE INDEX uk_login_log_logout_session ON freesia.sys_login_log(session_id)
WHERE event_type='LOGOUT' AND session_id IS NOT NULL;
