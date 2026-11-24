package com.freesia.system.iam.internal.organization.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.util.UUID;

@Table(value = "sys_organization", schema = "freesia")
public class OrganizationRow {
    @Id(keyType = KeyType.Auto) private UUID id;
    private UUID parentId;
    private String code;
    private String name;
    private String remark;
    private int sort;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }
}
