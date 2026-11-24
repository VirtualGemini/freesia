package com.freesia.system.iam.internal.organization.query;

import com.freesia.system.iam.internal.organization.dto.OrganizationNode;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class OrganizationQueries {
    private final JdbcClient jdbc;
    private final AuthorizationQueries authorization;

    public OrganizationQueries(JdbcClient jdbc,AuthorizationQueries authorization) { this.jdbc = jdbc;this.authorization=authorization; }

    public List<OrganizationNode> tree(UUID actor) {
        return treeFor(actor,"system:organization:query");
    }
    public List<OrganizationNode> treeFor(UUID actor,String permission) {
        var range=authorization.rights(actor).range(permission);
        var nodes = jdbc.sql("SELECT * FROM freesia.sys_organization ORDER BY sort,id")
                .query((rs, row) -> new OrganizationNode(rs.getObject("id", UUID.class),
                        rs.getObject("parent_id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("remark"), rs.getInt("sort"), rs.getBoolean("is_root"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), List.of())).list()
                .stream().filter(node->range.all()||range.organizationIds().contains(node.id())).toList();
        var visible=nodes.stream().map(OrganizationNode::id).collect(java.util.stream.Collectors.toSet());
        return nodes.stream().filter(node->node.parentId()==null||!visible.contains(node.parentId()))
                .map(node->withChildren(node,nodes)).toList();
    }

    private static List<OrganizationNode> branch(List<OrganizationNode> nodes, UUID parent) {
        return nodes.stream().filter(node -> Objects.equals(parent, node.parentId()))
                .map(node -> withChildren(node,nodes)).toList();
    }
    private static OrganizationNode withChildren(OrganizationNode node,List<OrganizationNode> nodes) {
        boolean parentVisible=node.parentId()==null||nodes.stream().anyMatch(candidate->candidate.id().equals(node.parentId()));
        return new OrganizationNode(node.id(),parentVisible?node.parentId():null,node.code(),node.name(),node.remark(),
                node.sort(),node.root(),node.createdAt(),node.updatedAt(),branch(nodes,node.id()));
    }
}
