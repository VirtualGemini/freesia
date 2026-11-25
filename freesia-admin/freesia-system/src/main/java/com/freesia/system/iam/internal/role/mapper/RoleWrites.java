package com.freesia.system.iam.internal.role.mapper;

import com.freesia.system.iam.internal.role.entity.RoleRow;
import com.freesia.system.iam.internal.role.dto.RoleInput;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.UUID;

@Mapper
public interface RoleWrites extends BaseMapper<RoleRow> {
    @Update("""
            UPDATE freesia.sys_role SET role_code=#{input.roleCode},role_name=#{input.roleName},
            description=#{input.description},data_scope=#{input.dataScope},enabled=#{input.enabled},updated_at=now() WHERE id=#{id}
            """)
    int changeDetails(@Param("id") UUID id, @Param("input") RoleInput input);
    @Delete("DELETE FROM freesia.sys_role_menu_permission WHERE role_id=#{id}")
    int clearUse(UUID id);
    @Delete("DELETE FROM freesia.sys_role_grant_permission WHERE role_id=#{id}")
    int clearGrant(UUID id);
    @Delete("DELETE FROM freesia.sys_role_scope_organization WHERE role_id=#{id}")
    int clearOrganizations(UUID id);
    @Insert("INSERT INTO freesia.sys_role_menu_permission(role_id,menu_id) VALUES(#{role},#{menu})")
    int addUse(@Param("role") UUID role, @Param("menu") UUID menu);
    @Insert("INSERT INTO freesia.sys_role_grant_permission(role_id,menu_id) VALUES(#{role},#{menu})")
    int addGrant(@Param("role") UUID role, @Param("menu") UUID menu);
    @Insert("INSERT INTO freesia.sys_role_scope_organization(role_id,organization_id) VALUES(#{role},#{organization})")
    int addOrganization(@Param("role") UUID role, @Param("organization") UUID organization);
    @Update("UPDATE freesia.sys_role SET enabled=#{enabled},updated_at=now() WHERE id=#{id}")
    int changeStatus(@Param("id")UUID id,@Param("enabled")boolean enabled);
    @Delete("DELETE FROM freesia.sys_account_role WHERE role_id=#{id}")
    int clearMembers(UUID id);
    @Update("UPDATE freesia.sys_role SET deleted=true,enabled=false,updated_at=now() WHERE id=#{id} AND NOT deleted AND NOT built_in")
    int markDeleted(UUID id);
}
