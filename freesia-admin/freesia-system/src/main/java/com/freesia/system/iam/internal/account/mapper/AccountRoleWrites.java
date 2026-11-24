package com.freesia.system.iam.internal.account.mapper;

import org.apache.ibatis.annotations.*;
import java.util.UUID;

@Mapper
public interface AccountRoleWrites {
    @Insert("INSERT INTO freesia.sys_account_role(account_id,role_id) VALUES(#{account},#{role}) ON CONFLICT DO NOTHING")
    int bind(@Param("account") UUID account, @Param("role") UUID role);
    @Delete("DELETE FROM freesia.sys_account_role WHERE account_id=#{account} AND role_id=#{role}")
    int unbind(@Param("account") UUID account, @Param("role") UUID role);
}
