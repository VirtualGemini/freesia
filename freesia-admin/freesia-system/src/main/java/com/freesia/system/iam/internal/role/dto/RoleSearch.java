package com.freesia.system.iam.internal.role.dto;

import java.time.Instant;

public record RoleSearch(String roleName,String roleCode,String description,Boolean enabled,Integer type,
                         Instant createTimeStart,Instant createTimeEnd,Instant updateTimeStart,Instant updateTimeEnd) {
    public boolean matches(RoleDetail role) {
        return (description==null||role.description().toLowerCase(java.util.Locale.ROOT).contains(description.toLowerCase(java.util.Locale.ROOT)))
                &&(enabled==null||role.enabled()==enabled)&&(type==null||role.type()==type)
                &&(createTimeStart==null||!role.createTime().isBefore(createTimeStart))
                &&(createTimeEnd==null||!role.createTime().isAfter(createTimeEnd))
                &&(updateTimeStart==null||!role.updateTime().isBefore(updateTimeStart))
                &&(updateTimeEnd==null||!role.updateTime().isAfter(updateTimeEnd));
    }
}
