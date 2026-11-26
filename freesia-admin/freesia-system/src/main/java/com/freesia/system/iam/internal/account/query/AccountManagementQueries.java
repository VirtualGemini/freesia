package com.freesia.system.iam.internal.account.query;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.result.PageResult;
import com.freesia.system.iam.internal.account.dto.*;
import com.freesia.system.iam.internal.authorization.dto.DataRange;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AccountManagementQueries {
    private final JdbcClient jdbc;
    private final AuthorizationQueries authorization;
    public AccountManagementQueries(JdbcClient jdbc, AuthorizationQueries authorization) {
        this.jdbc = jdbc; this.authorization = authorization;
    }
    public PageResult<ManagedAccount> list(UUID actor, String username, String email, String remark, String status, long current, long size, java.time.Instant createdAfter, java.time.Instant createdBefore,
            java.time.Instant updatedAfter, java.time.Instant updatedBefore) {
        if (!List.of("", "1", "2", "3").contains(status)) throw new ApiException(CommonErrorCode.BAD_REQUEST);
        DataRange range=authorization.rights(actor).range("system:account:query");
        Map<String,Object> params=new HashMap<>();
        params.put("actor", actor); params.put("username", "%"+username+"%"); params.put("email", "%"+email+"%");
        params.put("remark", "%"+remark+"%"); params.put("status", status);
        String condition = """
                FROM freesia.sys_account a LEFT JOIN freesia.sys_profile p ON p.account_id=a.id
                LEFT JOIN freesia.sys_organization o ON o.id=a.organization_id
                WHERE NOT a.deleted AND a.username ILIKE :username AND coalesce(p.email,'') ILIKE :email
                AND coalesce(a.remark,'') ILIKE :remark AND (:status='' OR CASE WHEN NOT a.enabled THEN '2' WHEN a.abnormal THEN '3' ELSE '1' END=:status)
                """+where(range,params);
        if(createdAfter!=null){condition+=" AND a.created_at>=:createdAfter";params.put("createdAfter",java.time.OffsetDateTime.ofInstant(createdAfter,java.time.ZoneOffset.UTC));}
        if(createdBefore!=null){condition+=" AND a.created_at<=:createdBefore";params.put("createdBefore",java.time.OffsetDateTime.ofInstant(createdBefore,java.time.ZoneOffset.UTC));}
        if(updatedAfter!=null){condition+=" AND a.updated_at>=:updatedAfter";params.put("updatedAfter",java.time.OffsetDateTime.ofInstant(updatedAfter,java.time.ZoneOffset.UTC));}
        if(updatedBefore!=null){condition+=" AND a.updated_at<=:updatedBefore";params.put("updatedBefore",java.time.OffsetDateTime.ofInstant(updatedBefore,java.time.ZoneOffset.UTC));}
        long page=Math.clamp(current,1,10000),limit=Math.clamp(size,1,100);
        long total=jdbc.sql("SELECT count(*) "+condition).params(params).query(Long.class).single();
        params.put("size",limit); params.put("offset",(page-1)*limit);
        var rows=jdbc.sql("""
                SELECT a.id,a.username,a.remark,a.organization_id,a.enabled,a.abnormal,a.created_at,a.updated_at,
                p.nickname,p.email,p.phone,o.name organization_name,
                EXISTS(SELECT 1 FROM freesia.sys_bootstrap b WHERE b.account_id=a.id) OR
                EXISTS(SELECT 1 FROM freesia.sys_account_role ar JOIN freesia.sys_role r ON r.id=ar.role_id
                       WHERE ar.account_id=a.id AND r.role_code='R_SUPER') protected_account
                """+condition+" ORDER BY a.created_at DESC,a.id DESC LIMIT :size OFFSET :offset")
                .params(params).query((rs,row) -> {
                    return new ManagedAccount(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("remark"),
                            rs.getObject("organization_id",UUID.class),rs.getString("organization_name"),rs.getString("email"),null,
                            rs.getBoolean("enabled")?(rs.getBoolean("abnormal")?"3":"1"):"2",null,rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getBoolean("protected_account"));
                }).list();
        return new PageResult<>(rows,page,limit,total);
    }
    public AccountDetailCard detail(UUID actor, UUID id) {
        authorization.account(actor,"system:account:query",id);
        var roles=jdbc.sql("""
                SELECT r.id,r.role_code,r.role_name FROM freesia.sys_account_role ar JOIN freesia.sys_role r ON r.id=ar.role_id
                WHERE ar.account_id=:id AND NOT r.deleted ORDER BY r.role_code
                """).param("id",id).query((rs,n)->new AccountDetailCard.AssignedRole(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3))).list();
        return jdbc.sql("""
                SELECT a.id,a.username,a.remark,a.organization_id,a.enabled,a.abnormal,a.created_at,a.updated_at,
                p.nickname,p.email,p.phone,o.name organization_name FROM freesia.sys_account a
                LEFT JOIN freesia.sys_profile p ON p.account_id=a.id
                LEFT JOIN freesia.sys_organization o ON o.id=a.organization_id WHERE a.id=:id AND NOT a.deleted
                """).param("id",id).query((rs,n)->{
                    var snapshot=new AccountDetailCard.Snapshot(id,rs.getString("username"),rs.getString("nickname"),rs.getString("remark"),
                            rs.getObject("organization_id",UUID.class),rs.getString("organization_name"),null,rs.getBoolean("enabled")?(rs.getBoolean("abnormal")?"3":"1"):"2",null,
                            rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),roles,
                            roles.stream().map(AccountDetailCard.AssignedRole::roleCode).toList(),false);
                    var profile=new AccountDetailCard.Profile(rs.getString("nickname"),rs.getString("email"),rs.getString("phone"),List.of());
                    return new AccountDetailCard(snapshot,profile,snapshot,new AccountDetailCard.Security(false,false,List.of("password"),List.of(),List.of("password")),List.of());
                }).optional().orElseThrow(()->new ApiException(CommonErrorCode.NOT_FOUND));
    }
    public VisibleRoleMembers members(UUID actor, UUID role) {
        DataRange range=authorization.rights(actor).range("system:account:query");
        var params=new HashMap<String,Object>();params.put("actor",actor);params.put("role",role);
        var members=jdbc.sql("""
                SELECT a.id,a.username,p.nickname FROM freesia.sys_account a LEFT JOIN freesia.sys_profile p ON p.account_id=a.id JOIN freesia.sys_account_role ar ON ar.account_id=a.id
                WHERE ar.role_id=:role AND NOT a.deleted
                """+where(range,params)+" ORDER BY a.username,a.id").params(params)
                .query((rs,n)->new VisibleRoleMembers.Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3))).list();
        return new VisibleRoleMembers(members,members.size());
    }
    private static String where(DataRange range,Map<String,Object> params) {
        if(range.all())return "";
        String self=range.self()?"a.id=:actor":"false";
        if(range.organizationIds().isEmpty())return " AND ("+self+")";
        params.put("scopeOrganizations",range.organizationIds());
        return " AND ("+self+" OR a.organization_id IN (:scopeOrganizations))";
    }
}
