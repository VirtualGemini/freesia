package com.freesia.system.iam.internal.account.mapper;

import com.freesia.system.iam.internal.account.entity.AccountRow;
import com.freesia.system.iam.internal.account.dto.AccountProfileInput;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface AccountWrites extends BaseMapper<AccountRow> {
    @Insert("INSERT INTO freesia.sys_profile(account_id) VALUES(#{accountId})")
    int createProfile(UUID accountId);

    @Update("""
            UPDATE freesia.sys_account SET password_hash=#{replacement}, updated_at=now()
            WHERE id=#{id} AND password_hash=#{expected} AND enabled AND NOT deleted
            """)
    int upgradePassword(@Param("id") UUID id, @Param("expected") String expected, @Param("replacement") String replacement);
    @Update("""
            UPDATE freesia.sys_account SET username=#{input.username},remark=#{input.remark},updated_at=now() WHERE id=#{id}
            """)
    int changeBasic(@Param("id")UUID id,@Param("input")AccountProfileInput input);
    @Update("""
            UPDATE freesia.sys_profile SET nickname=#{input.nickname},email=#{input.email},phone=#{input.phone} WHERE account_id=#{id}
            """)
    int changeProfile(@Param("id")UUID id,@Param("input")AccountProfileInput input);
    @Update("""
            UPDATE freesia.sys_profile p SET language=#{language}
            FROM freesia.sys_account a
            WHERE p.account_id=#{id} AND a.id=p.account_id AND a.enabled AND NOT a.deleted
            """)
    int changeLanguage(@Param("id") UUID id, @Param("language") String language);
    @Update("UPDATE freesia.sys_account SET organization_id=#{organization},updated_at=now() WHERE id=#{id}")
    int moveOrganization(@Param("id")UUID id,@Param("organization")UUID organization);
    @Update("""
            UPDATE freesia.sys_account SET enabled=#{enabled},abnormal=#{abnormal},
            session_version=session_version+CASE WHEN #{enabled} THEN 0 ELSE 1 END,updated_at=now()
            WHERE id=#{id} AND NOT deleted
            """)
    int changeStatus(@Param("id") UUID id, @Param("enabled") boolean enabled, @Param("abnormal") boolean abnormal);
    @Delete("DELETE FROM freesia.sys_account_role WHERE account_id=#{id}")
    int clearRoles(UUID id);
    @Update("""
            UPDATE freesia.sys_account SET deleted=true,enabled=false,abnormal=false,deleted_at=now(),
            session_version=session_version+1,updated_at=now() WHERE id=#{id} AND NOT deleted AND NOT enabled
            """)
    int markDeleted(UUID id);

    @Update("""
            UPDATE freesia.sys_account SET password_hash=#{hash},must_change_password=false,
            session_version=session_version+1,updated_at=now()
            WHERE id=#{id} AND enabled AND NOT deleted
            """)
    int resetPassword(@Param("id") UUID id, @Param("hash") String hash);
}
