package com.freesia.system.iam.internal.bootstrap.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface BootstrapWrites {
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
}
