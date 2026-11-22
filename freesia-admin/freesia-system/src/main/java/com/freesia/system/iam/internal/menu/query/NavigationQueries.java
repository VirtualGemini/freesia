package com.freesia.system.iam.internal.menu.query;

import com.freesia.system.iam.internal.menu.dto.Route;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class NavigationQueries {
    private final JdbcClient jdbc;
    public NavigationQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    private record Node(UUID id, UUID parent, String name, String path, String component, String redirect, Map<String,Object> meta) {}

    public List<Route> navigation(UUID account) {
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
        return branch(nodes, null, new HashSet<>());
    }
    private static List<Route> branch(List<Node> nodes, UUID parent, Set<UUID> visited) {
        List<Route> result = new ArrayList<>();
        for (Node node : nodes) if (Objects.equals(parent, node.parent()) && visited.add(node.id())) {
            result.add(new Route(node.id(), node.name(), node.path(), node.component(), node.redirect(), node.meta(), branch(nodes, node.id(), visited)));
        }
        return result;
    }
}
