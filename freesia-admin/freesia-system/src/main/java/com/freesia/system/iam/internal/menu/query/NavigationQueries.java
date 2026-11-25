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
                WITH RECURSIVE authorized AS (
                SELECT DISTINCT m.id,m.parent_id FROM freesia.sys_menu m
                JOIN freesia.sys_role_menu_permission rm ON rm.menu_id=m.id
                JOIN freesia.sys_role r ON r.id=rm.role_id JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted
                AND m.enabled AND NOT m.deleted AND m.menu_type='menu'
                UNION
                SELECT parent.id,parent.parent_id FROM freesia.sys_menu parent JOIN authorized child ON parent.id=child.parent_id
                WHERE parent.enabled AND NOT parent.deleted AND parent.menu_type='menu')
                SELECT m.* FROM freesia.sys_menu m JOIN authorized granted ON granted.id=m.id ORDER BY m.sort,m.id
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
        // Retain the legacy route-button contract consumed by v-auth, using M only.
        var buttons = jdbc.sql("""
                SELECT DISTINCT m.id,m.parent_id,m.title,m.auth_mark,m.sort FROM freesia.sys_menu m
                JOIN freesia.sys_role_menu_permission p ON p.menu_id=m.id
                JOIN freesia.sys_role r ON r.id=p.role_id JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted
                AND m.enabled AND NOT m.deleted AND m.menu_type='button' ORDER BY m.sort,m.id
                """).param("id", account).query((rs, row) -> {
            Map<String,Object> button = new LinkedHashMap<>();
            button.put("id", rs.getObject("id", UUID.class));
            button.put("title", rs.getString("title"));
            button.put("authMark", rs.getString("auth_mark"));
            button.put("sort", rs.getInt("sort"));
            return new Button(rs.getObject("parent_id", UUID.class), button);
        }).list();
        for (Node node : nodes) {
            node.meta().put("authList", buttons.stream().filter(button -> node.id().equals(button.parent()))
                    .map(Button::meta).toList());
        }
        return branch(nodes, null, new HashSet<>());
    }
    private static List<Route> branch(List<Node> nodes, UUID parent, Set<UUID> visited) {
        List<Route> result = new ArrayList<>();
        for (Node node : nodes) if (Objects.equals(parent, node.parent()) && visited.add(node.id())) {
            result.add(new Route(node.id(), node.parent(), node.name(), node.path(), node.component(), node.redirect(), node.meta(), branch(nodes, node.id(), visited)));
        }
        return result;
    }
    private record Button(UUID parent, Map<String,Object> meta) {}
}
