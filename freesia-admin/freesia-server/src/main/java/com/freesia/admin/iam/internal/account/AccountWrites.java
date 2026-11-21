package com.freesia.admin.iam.internal.account;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface AccountWrites extends BaseMapper<AccountRow> {
    @Insert("INSERT INTO freesia.sys_profile(account_id) VALUES(#{accountId})")
    int createProfile(UUID accountId);

    @Insert("""
            INSERT INTO freesia.sys_account_role(account_id, role_id)
            SELECT #{accountId}, id FROM freesia.sys_role
            WHERE role_code='R_SUPER' AND enabled AND NOT deleted
            """)
    int bindFirstSuper(UUID accountId);

    @Update("""
            UPDATE freesia.sys_bootstrap SET account_id=#{accountId}, completed_at=now()
            WHERE purpose='first-super' AND completed_at IS NULL
            """)
    int completeBootstrap(UUID accountId);

    @Update("""
            UPDATE freesia.sys_account SET password_hash=#{replacement}, updated_at=now()
            WHERE id=#{id} AND password_hash=#{expected} AND enabled AND NOT deleted
            """)
    int upgradePassword(@Param("id") UUID id, @Param("expected") String expected, @Param("replacement") String replacement);
}
