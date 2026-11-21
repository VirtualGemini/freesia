package com.freesia.admin.iam.internal.navigation;

import com.freesia.admin.iam.internal.auth.Sessions;
import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
class NavigationQueries {
    private final JdbcClient jdbc;
    private final Sessions sessions;
    NavigationQueries(JdbcClient jdbc, Sessions sessions) { this.jdbc = jdbc; this.sessions = sessions; }

    record Route(UUID id, String name, String path, String component, String redirect,
                 Map<String, Object> meta, List<Route> children) {}
    record Node(UUID id, UUID parent, String name, String path, String component, String redirect, Map<String,Object> meta) {}

    @RequireAuthenticated
    @GetMapping("/api/v3/system/menus/simple")
    R<List<Route>> navigation(@RequestHeader("Authorization") String token) {
        UUID account = sessions.authenticate(token);
        var nodes = jdbc.sql("""
                SELECT DISTINCT m.* FROM freesia.sys_menu m
                JOIN freesia.sys_role_menu_permission rm ON rm.menu_id=m.id
                JOIN freesia.sys_role r ON r.id=rm.role_id JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted
                AND m.enabled AND NOT m.deleted AND m.menu_type='menu' ORDER BY m.sort,m.id
                """).param("id", account).query((rs, row) -> {
            Map<String,Object> meta = new LinkedHashMap<>();
            for (String flag : List.of("keep_alive","is_hide","is_hide_tab","is_iframe","show_badge","fixed_tab","is_full_page")) {
                String name = switch(flag) {
                    case "keep_alive" -> "keepAlive"; case "is_hide" -> "isHide"; case "is_hide_tab" -> "isHideTab";
                    case "is_iframe" -> "isIframe"; case "show_badge" -> "showBadge"; case "fixed_tab" -> "fixedTab";
                    default -> "isFullPage";
                };
                meta.put(name, rs.getBoolean(flag));
            }
            meta.put("title", rs.getString("title")); meta.put("icon", rs.getString("icon"));
            meta.put("link", rs.getString("link")); meta.put("authMark", rs.getString("auth_mark"));
            meta.put("activePath", rs.getString("active_path")); meta.put("showTextBadge", rs.getString("show_text_badge"));
            return new Node(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                    rs.getString("name"), rs.getString("path"), rs.getString("component"), rs.getString("redirect"), meta);
        }).list();
        return R.ok(branch(nodes, null, new HashSet<>()));
    }
    private static List<Route> branch(List<Node> nodes, UUID parent, Set<UUID> visited) {
        List<Route> result = new ArrayList<>();
        for (Node node : nodes) if (Objects.equals(parent, node.parent()) && visited.add(node.id())) {
            result.add(new Route(node.id(), node.name(), node.path(), node.component(), node.redirect(), node.meta(), branch(nodes, node.id(), visited)));
        }
        return result;
    }
}
