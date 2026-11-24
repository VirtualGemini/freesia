package com.freesia.system.iam.internal.organization.mapper;

import com.freesia.system.iam.internal.organization.entity.OrganizationRow;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import com.freesia.system.iam.internal.organization.dto.OrganizationInput;
import java.util.UUID;

@Mapper
public interface OrganizationWrites extends BaseMapper<OrganizationRow> {
    @Update("""
            UPDATE freesia.sys_organization SET parent_id=#{input.parentId}, code=#{input.code},
                name=#{input.name},remark=#{input.remark},sort=#{input.sort},updated_at=now() WHERE id=#{id}
            """)
    int changeDetails(@Param("id") UUID id, @Param("input") OrganizationInput input);
    @Delete("DELETE FROM freesia.sys_organization WHERE id=#{id}")
    int remove(UUID id);
}
