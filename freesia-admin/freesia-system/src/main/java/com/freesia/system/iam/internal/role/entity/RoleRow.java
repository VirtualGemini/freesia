package com.freesia.system.iam.internal.role.entity;

import com.mybatisflex.annotation.*;
import java.util.UUID;

@Table(value = "sys_role", schema = "freesia")
public class RoleRow {
    @Id(keyType = KeyType.Auto) private UUID id;
    private String roleCode;
    private String roleName;
    private String description;
    private String dataScope;
    private boolean enabled;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
