package com.freesia.system.iam.internal.menu.mapper;

import com.freesia.system.iam.internal.menu.entity.MenuRow;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.UUID;

@Mapper
public interface MenuWrites extends BaseMapper<MenuRow> {
    @Update("""
            UPDATE freesia.sys_menu SET parent_id=#{menu.parentId},menu_type=#{menu.menuType},
              name=#{menu.name},title=#{menu.title},path=#{menu.path},component=#{menu.component},
              redirect=#{menu.redirect},icon=#{menu.icon},auth_mark=#{menu.authMark},sort=#{menu.sort},
              enabled=#{menu.isEnable},keep_alive=#{menu.keepAlive},is_hide=#{menu.isHide},
              is_hide_tab=#{menu.isHideTab},link=#{menu.link},is_iframe=#{menu.isIframe},
              show_badge=#{menu.showBadge},show_text_badge=#{menu.showTextBadge},fixed_tab=#{menu.fixedTab},
              active_path=#{menu.activePath},is_full_page=#{menu.isFullPage},updated_at=now()
            WHERE id=#{menu.id} AND NOT deleted
            """)
    int changeDetails(@Param("menu") MenuRow menu);

    @Insert("""
            INSERT INTO freesia.sys_role_menu_permission(role_id,menu_id)
            SELECT id,#{id} FROM freesia.sys_role WHERE role_code='R_SUPER' AND built_in AND NOT deleted
            """)
    int ownerUse(UUID id);

    @Insert("""
            INSERT INTO freesia.sys_role_grant_permission(role_id,menu_id)
            SELECT id,#{id} FROM freesia.sys_role WHERE role_code='R_SUPER' AND built_in AND NOT deleted
            """)
    int ownerGrant(UUID id);

    @Delete("DELETE FROM freesia.sys_role_menu_permission WHERE menu_id=#{id}")
    int removeUse(UUID id);

    @Delete("DELETE FROM freesia.sys_role_grant_permission WHERE menu_id=#{id}")
    int removeGrant(UUID id);

    @Update("UPDATE freesia.sys_menu SET deleted=true,updated_at=now() WHERE id=#{id} AND NOT deleted")
    int remove(UUID id);
}
