-- Fresh product navigation, not imported historical business data or credentials.
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
