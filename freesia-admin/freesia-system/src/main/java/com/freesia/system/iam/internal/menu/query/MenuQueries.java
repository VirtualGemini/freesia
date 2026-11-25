package com.freesia.system.iam.internal.menu.query;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.menu.dto.Route;
import com.freesia.system.iam.internal.menu.entity.MenuRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/** Tree and metadata conversion adapted from Velox 9dd4f30a MenuServiceImpl. */
@Component
public class MenuQueries {
    private final JdbcClient jdbc;
    private final AuthorizationQueries authorization;

    public MenuQueries(JdbcClient jdbc, AuthorizationQueries authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public void requireGlobal(UUID actor, String permission) {
        if (!authorization.rights(actor).range(permission).all()) {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }
    }

    public List<Route> directory(UUID actor) {
        requireGlobal(actor, "system:menu:query");
        var nodes = jdbc.sql("""
                SELECT m.*, coalesce(m.parent_id, (
                    SELECT parent.id FROM freesia.sys_menu parent
                    WHERE m.menu_type='button' AND parent.menu_type='menu' AND NOT parent.deleted
                    AND lower(parent.name)=split_part(m.auth_mark,':',2)
                    ORDER BY parent.sort,parent.id LIMIT 1
                )) tree_parent FROM freesia.sys_menu m WHERE NOT m.deleted ORDER BY m.sort,m.id
                """).query((rs, n) -> {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("title", rs.getString("title"));
            meta.put("icon", rs.getString("icon"));
            meta.put("authMark", rs.getString("auth_mark"));
            meta.put("isEnable", rs.getBoolean("enabled"));
            meta.put("sort", rs.getInt("sort"));
            meta.put("keepAlive", rs.getBoolean("keep_alive"));
            meta.put("isHide", rs.getBoolean("is_hide"));
            meta.put("isHideTab", rs.getBoolean("is_hide_tab"));
            meta.put("link", rs.getString("link"));
            meta.put("isIframe", rs.getBoolean("is_iframe"));
            meta.put("showBadge", rs.getBoolean("show_badge"));
            meta.put("showTextBadge", rs.getString("show_text_badge"));
            meta.put("fixedTab", rs.getBoolean("fixed_tab"));
            meta.put("activePath", rs.getString("active_path"));
            meta.put("isFullPage", rs.getBoolean("is_full_page"));
            meta.put("createTime", rs.getTimestamp("created_at").toInstant());
            meta.put("updateTime", rs.getTimestamp("updated_at").toInstant());
            return new Node(rs.getObject("id", UUID.class), rs.getObject("tree_parent", UUID.class),
                    rs.getString("menu_type"), rs.getString("name"), rs.getString("path"),
                    rs.getString("component"), rs.getString("redirect"), meta);
        }).list();
        return branch(nodes, null);
    }

    public MenuRow raw(UUID id) {
        return jdbc.sql("SELECT *,enabled AS is_enable FROM freesia.sys_menu WHERE id=:id AND NOT deleted")
                .param("id", id).query(MenuRow.class).optional()
                .orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
    }

    public boolean hasChildren(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_menu WHERE parent_id=:id AND NOT deleted)")
                .param("id", id).query(Boolean.class).single();
    }

    public boolean hasMenuChildren(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_menu WHERE parent_id=:id AND menu_type='menu' AND NOT deleted)")
                .param("id", id).query(Boolean.class).single();
    }

    public boolean below(UUID parent, UUID id) {
        return jdbc.sql("""
                WITH RECURSIVE ancestors AS (
                  SELECT id,parent_id FROM freesia.sys_menu WHERE id=:parent AND NOT deleted
                  UNION SELECT m.id,m.parent_id FROM freesia.sys_menu m JOIN ancestors a ON m.id=a.parent_id
                ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=:id)
                """).param("parent", parent).param("id", id).query(Boolean.class).single();
    }

    public boolean duplicate(String name, String authMark, UUID exclude) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM freesia.sys_menu WHERE NOT deleted
                  AND (:id::uuid IS NULL OR id<>:id)
                  AND (name=:name OR auth_mark=:auth))
                """).param("id", exclude).param("name", name).param("auth", authMark)
                .query(Boolean.class).single();
    }

    public boolean hasOrdinaryBindings(UUID id) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM freesia.sys_role r JOIN (
                    SELECT role_id,menu_id FROM freesia.sys_role_menu_permission UNION ALL
                    SELECT role_id,menu_id FROM freesia.sys_role_grant_permission
                  ) p ON p.role_id=r.id WHERE p.menu_id=:id AND NOT r.built_in AND NOT r.deleted)
                """).param("id", id).query(Boolean.class).single();
    }

    /** Read the actual post-write state in the caller's transaction, including PostgreSQL sort/UUID order. */
    public boolean validRedirects() {
        var nodes = new LinkedHashMap<UUID, RedirectNode>();
        jdbc.sql("""
                SELECT id,parent_id,path,component,redirect,link,enabled FROM freesia.sys_menu
                WHERE menu_type='menu' AND NOT deleted ORDER BY sort,id
                """).query((rs, row) -> new RedirectNode(rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("path"), rs.getString("component"),
                rs.getString("redirect"), rs.getString("link"), rs.getBoolean("enabled")))
                .list().forEach(node -> nodes.put(node.id(), node));
        var byPath = new HashMap<String, RedirectNode>();
        for (var node : nodes.values()) {
            String path = fullPath(node, nodes, new HashSet<>());
            if (path != null) byPath.put(path, node);
        }
        return nodes.values().stream().filter(node -> node.redirect() != null)
                .allMatch(node -> validRedirect(node, nodes, byPath));
    }

    private static boolean validRedirect(RedirectNode source, Map<UUID, RedirectNode> nodes,
                                         Map<String, RedirectNode> byPath) {
        String sourcePath = fullPath(source, nodes, new HashSet<>());
        String target = source.redirect();
        Set<UUID> visited = new HashSet<>();
        visited.add(source.id());
        while (target != null) {
            if (!target.startsWith("/") || target.startsWith("//")) return false;
            String path = target.split("[?#]", 2)[0];
            if (Objects.equals(sourcePath, path)) return false;
            RedirectNode node = byPath.get(path);
            if (node == null || !node.enabled() || node.link() != null || !visited.add(node.id())) return false;
            if (node.redirect() == null) {
                RedirectNode child = defaultChild(node.id(), nodes, new HashSet<>());
                if (child == null) return node.component() != null;
                target = fullPath(child, nodes, new HashSet<>());
            } else {
                target = node.redirect();
            }
        }
        return false;
    }

    /** Match the existing frontend's first usable descendant when a directory has no explicit redirect. */
    private static RedirectNode defaultChild(UUID parent, Map<UUID, RedirectNode> nodes, Set<UUID> visited) {
        if (!visited.add(parent)) return null;
        for (var child : nodes.values()) {
            if (!parent.equals(child.parent()) || !child.enabled()) continue;
            if (child.path() != null && !"/".equals(child.path()) && child.link() == null && child.component() != null) {
                return child;
            }
            RedirectNode nested = defaultChild(child.id(), nodes, visited);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String fullPath(RedirectNode node, Map<UUID, RedirectNode> nodes, Set<UUID> visited) {
        if (node == null || node.path() == null || node.id() != null && !visited.add(node.id())) return null;
        if (node.path().startsWith("/")) return node.path();
        String parent = node.parent() == null ? "" : fullPath(nodes.get(node.parent()), nodes, visited);
        return parent == null ? null : parent.replaceAll("/+$", "") + "/" + node.path();
    }

    private static List<Route> branch(List<Node> nodes, UUID parent) {
        List<Route> result = new ArrayList<>();
        for (Node node : nodes) {
            if (!Objects.equals(node.parent(), parent)) continue;
            if ("button".equals(node.type())) {
                if (parent != null) continue;
                node.meta().put("isAuthButton", true);
            } else {
                var buttons = nodes.stream().filter(child -> node.id().equals(child.parent()) && "button".equals(child.type()))
                        .map(child -> {
                            var auth = new LinkedHashMap<>(child.meta());
                            auth.put("id", child.id());
                            return auth;
                        }).toList();
                node.meta().put("authList", buttons);
            }
            result.add(new Route(node.id(), node.parent(), node.name(), node.path(), node.component(), node.redirect(),
                    node.meta(), branch(nodes, node.id())));
        }
        return result;
    }

    private record Node(UUID id, UUID parent, String type, String name, String path, String component,
                        String redirect, Map<String, Object> meta) {}
    private record RedirectNode(UUID id, UUID parent, String path, String component, String redirect,
                                String link, boolean enabled) {}
}
