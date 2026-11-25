package com.freesia.system.iam.internal.menu.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.menu.dto.MenuSaveCommand;
import com.freesia.system.iam.internal.menu.entity.MenuRow;
import com.freesia.system.iam.internal.menu.mapper.MenuWrites;
import com.freesia.system.iam.internal.menu.query.MenuQueries;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.freesia.system.iam.internal.menu.dto.MenuSaveCommand.Field.*;

/** Velox MenuServiceImpl command application/validation adapted to current transactions and persistence. */
@Component
public class SaveMenu {
    private final MenuWrites writes;
    private final MenuQueries menus;
    private final ManagementActions actions;

    public SaveMenu(MenuWrites writes, MenuQueries menus, ManagementActions actions) {
        this.writes = writes;
        this.menus = menus;
        this.actions = actions;
    }

    public UUID save(UUID actor, UUID id, MenuSaveCommand input, String trace) {
        String permission = id == null ? "system:menu:create" : "system:menu:update";
        return actions.run(actor, permission, "menu", id == null ? null : id.toString(), trace, () -> {
            menus.requireGlobal(actor, permission);
            MenuRow menu = id == null ? defaults() : menus.raw(id);
            String oldType = menu.getMenuType();
            String oldPermission = menu.getAuthMark();
            boolean directory = id != null && menu.getLink() == null && menu.getRedirect() == null
                    && (menu.getComponent() == null || "/index/index".equals(menu.getComponent()));
            applyCommand(menu, input);
            if (id != null && (!Objects.equals(oldType, menu.getMenuType())
                    || !Objects.equals(oldPermission, menu.getAuthMark()) && menus.hasOrdinaryBindings(id))) {
                throw new ApiException(IamErrorCode.MENU_PERMISSION_BOUND);
            }
            if (id == null && menu.getLink() == null && menu.getComponent() == null && menu.getRedirect() == null) {
                directory = true; // The retained editor represents a new directory by an empty component.
            }
            validateMenuCommand(menu, directory, id != null && !input.has(PARENT_ID));
            if (id == null) {
                writes.insertSelective(menu);
                // Only the exclusive built-in role retains its existing all-capabilities invariant.
                writes.ownerUse(menu.getId());
                writes.ownerGrant(menu.getId());
            } else {
                writes.changeDetails(menu);
            }
            if (!menus.validRedirects()) throw new ApiException(IamErrorCode.MENU_ROUTE_REQUIRED);
            return menu.getId();
        });
    }

    public boolean delete(UUID actor, UUID id, String trace) {
        return actions.run(actor, "system:menu:delete", "menu", id.toString(), trace, () -> {
            menus.requireGlobal(actor, "system:menu:delete");
            menus.raw(id);
            if (menus.hasChildren(id)) throw new ApiException(IamErrorCode.MENU_HAS_CHILDREN);
            writes.removeUse(id);
            writes.removeGrant(id);
            writes.remove(id);
            if (!menus.validRedirects()) throw new ApiException(IamErrorCode.MENU_ROUTE_REQUIRED);
            return true;
        });
    }

    private static MenuRow defaults() {
        var row = new MenuRow();
        row.setIsEnable(true);
        row.setSort(1);
        row.setKeepAlive(false);
        row.setIsHide(false);
        row.setIsHideTab(false);
        row.setIsIframe(false);
        row.setShowBadge(false);
        row.setFixedTab(false);
        row.setIsFullPage(false);
        return row;
    }

    private static void applyCommand(MenuRow menu, MenuSaveCommand command) {
        if (command.has(PARENT_ID)) {
            String parent = normalizeNullable(command.getParentId());
            try { menu.setParentId(parent == null ? null : UUID.fromString(parent)); }
            catch (IllegalArgumentException invalid) { throw new ApiException(CommonErrorCode.BAD_REQUEST); }
        }
        if (command.has(MENU_TYPE)) {
            String type = normalizeNullable(command.getMenuType());
            menu.setMenuType(type == null ? null : type.toLowerCase(Locale.ROOT));
        }
        if (command.has(NAME)) menu.setName(normalizeNullable(command.getName()));
        if (command.has(TITLE)) menu.setTitle(normalizeNullable(command.getTitle()));
        if (command.has(PATH)) menu.setPath(normalizeNullable(command.getPath()));
        if (command.has(COMPONENT)) menu.setComponent(normalizeNullable(command.getComponent()));
        if (command.has(REDIRECT)) menu.setRedirect(normalizeNullable(command.getRedirect()));
        if (command.has(ICON)) menu.setIcon(normalizeNullable(command.getIcon()));
        if (command.has(AUTH_MARK)) menu.setAuthMark(normalizeNullable(command.getAuthMark()));
        if (command.has(IS_ENABLE)) menu.setIsEnable(command.getIsEnable());
        if (command.has(SORT)) menu.setSort(command.getSort());
        if (command.has(KEEP_ALIVE)) menu.setKeepAlive(command.getKeepAlive());
        if (command.has(IS_HIDE)) menu.setIsHide(command.getIsHide());
        if (command.has(IS_HIDE_TAB)) menu.setIsHideTab(command.getIsHideTab());
        if (command.has(LINK)) menu.setLink(normalizeNullable(command.getLink()));
        if (command.has(IS_IFRAME)) menu.setIsIframe(command.getIsIframe());
        if (command.has(SHOW_BADGE)) menu.setShowBadge(command.getShowBadge());
        if (command.has(SHOW_TEXT_BADGE)) menu.setShowTextBadge(normalizeNullable(command.getShowTextBadge()));
        if (command.has(FIXED_TAB)) menu.setFixedTab(command.getFixedTab());
        if (command.has(ACTIVE_PATH)) menu.setActivePath(normalizeNullable(command.getActivePath()));
        if (command.has(IS_FULL_PAGE)) menu.setIsFullPage(command.getIsFullPage());
        if (menu.getLink() == null) menu.setIsIframe(false);
        if ("button".equals(menu.getMenuType())) {
            menu.setPath(null);
            menu.setComponent(null);
            menu.setRedirect(null);
            menu.setLink(null);
            menu.setIsIframe(false);
        }
    }

    private void validateMenuCommand(MenuRow menu, boolean directory, boolean unchangedRootButton) {
        if (menu.getMenuType() == null || !Set.of("menu", "button").contains(menu.getMenuType())
                || menu.getName() == null || menu.getTitle() == null
                || menu.getIsEnable() == null || menu.getSort() == null
                || menu.getKeepAlive() == null || menu.getIsHide() == null || menu.getIsHideTab() == null
                || menu.getIsIframe() == null || menu.getShowBadge() == null || menu.getFixedTab() == null
                || menu.getIsFullPage() == null) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        limit(menu.getName(), 100); limit(menu.getTitle(), 120); limit(menu.getAuthMark(), 120);
        limit(menu.getPath(), 255); limit(menu.getComponent(), 255); limit(menu.getRedirect(), 255);
        limit(menu.getIcon(), 100); limit(menu.getLink(), 2048); limit(menu.getShowTextBadge(), 80);
        limit(menu.getActivePath(), 255);
        if (menu.getAuthMark() != null && !menu.getAuthMark().matches("[A-Za-z0-9_.:-]+")) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        if (menu.getParentId() != null) {
            MenuRow parent = menus.raw(menu.getParentId());
            if ("button".equals(parent.getMenuType())) throw new ApiException(IamErrorCode.MENU_PARENT_INVALID);
            if (menu.getId() != null && menus.below(menu.getParentId(), menu.getId())) {
                throw new ApiException(IamErrorCode.MENU_PARENT_INVALID);
            }
        } else if ("button".equals(menu.getMenuType()) && !unchangedRootButton) {
            throw new ApiException(IamErrorCode.MENU_PARENT_INVALID);
        }
        if ("menu".equals(menu.getMenuType())) {
            if (menu.getPath() == null) throw new ApiException(IamErrorCode.MENU_ROUTE_REQUIRED);
            if (menu.getLink() == null && menu.getComponent() == null && menu.getRedirect() == null
                    && !directory && (menu.getId() == null || !menus.hasMenuChildren(menu.getId()))) {
                throw new ApiException(IamErrorCode.MENU_ROUTE_REQUIRED);
            }
            if (menu.getLink() != null) {
                try {
                    URI link = URI.create(menu.getLink());
                    if (link.getScheme() == null || !Set.of("http", "https").contains(link.getScheme())
                            || link.getHost() == null) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new ApiException(CommonErrorCode.BAD_REQUEST);
                }
            }
        }
        if (menus.duplicate(menu.getName(), menu.getAuthMark(), menu.getId())) {
            throw new ApiException(IamErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private static void limit(String value, int max) {
        if (value != null && value.length() > max) throw new ApiException(CommonErrorCode.BAD_REQUEST);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
