package com.freesia.system.iam.internal.menu.dto;

import java.util.EnumSet;
import java.util.Set;

/** Velox 9dd4f30a command; setter presence distinguishes omission from explicit clearing. */
public class MenuSaveCommand {
    public enum Field { PARENT_ID, MENU_TYPE, NAME, TITLE, PATH, COMPONENT, REDIRECT, ICON, AUTH_MARK, IS_ENABLE, SORT, KEEP_ALIVE, IS_HIDE, IS_HIDE_TAB, LINK, IS_IFRAME, SHOW_BADGE, SHOW_TEXT_BADGE, FIXED_TAB, ACTIVE_PATH, IS_FULL_PAGE }

    private final Set<Field> supplied = EnumSet.noneOf(Field.class);
    public boolean has(Field field) { return supplied.contains(field); }

    private String parentId;

    private String menuType;

    private String name;

    private String title;

    private String path;

    private String component;

    private String redirect;

    private String icon;

    private String authMark;

    private Boolean isEnable;

    private Integer sort;

    private Boolean keepAlive;
    private Boolean isHide;
    private Boolean isHideTab;

    private String link;

    private Boolean isIframe;
    private Boolean showBadge;

    private String showTextBadge;

    private Boolean fixedTab;

    private String activePath;

    private Boolean isFullPage;

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        supplied.add(Field.PARENT_ID);
        this.parentId = parentId;
    }

    public String getMenuType() {
        return menuType;
    }

    public void setMenuType(String menuType) {
        supplied.add(Field.MENU_TYPE);
        this.menuType = menuType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        supplied.add(Field.NAME);
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        supplied.add(Field.TITLE);
        this.title = title;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        supplied.add(Field.PATH);
        this.path = path;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        supplied.add(Field.COMPONENT);
        this.component = component;
    }

    public String getRedirect() {
        return redirect;
    }

    public void setRedirect(String redirect) {
        supplied.add(Field.REDIRECT);
        this.redirect = redirect;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        supplied.add(Field.ICON);
        this.icon = icon;
    }

    public String getAuthMark() {
        return authMark;
    }

    public void setAuthMark(String authMark) {
        supplied.add(Field.AUTH_MARK);
        this.authMark = authMark;
    }

    public Boolean getIsEnable() {
        return isEnable;
    }

    public void setIsEnable(Boolean isEnable) {
        supplied.add(Field.IS_ENABLE);
        this.isEnable = isEnable;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        supplied.add(Field.SORT);
        this.sort = sort;
    }

    public Boolean getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(Boolean keepAlive) {
        supplied.add(Field.KEEP_ALIVE);
        this.keepAlive = keepAlive;
    }

    public Boolean getIsHide() {
        return isHide;
    }

    public void setIsHide(Boolean isHide) {
        supplied.add(Field.IS_HIDE);
        this.isHide = isHide;
    }

    public Boolean getIsHideTab() {
        return isHideTab;
    }

    public void setIsHideTab(Boolean isHideTab) {
        supplied.add(Field.IS_HIDE_TAB);
        this.isHideTab = isHideTab;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        supplied.add(Field.LINK);
        this.link = link;
    }

    public Boolean getIsIframe() {
        return isIframe;
    }

    public void setIsIframe(Boolean isIframe) {
        supplied.add(Field.IS_IFRAME);
        this.isIframe = isIframe;
    }

    public Boolean getShowBadge() {
        return showBadge;
    }

    public void setShowBadge(Boolean showBadge) {
        supplied.add(Field.SHOW_BADGE);
        this.showBadge = showBadge;
    }

    public String getShowTextBadge() {
        return showTextBadge;
    }

    public void setShowTextBadge(String showTextBadge) {
        supplied.add(Field.SHOW_TEXT_BADGE);
        this.showTextBadge = showTextBadge;
    }

    public Boolean getFixedTab() {
        return fixedTab;
    }

    public void setFixedTab(Boolean fixedTab) {
        supplied.add(Field.FIXED_TAB);
        this.fixedTab = fixedTab;
    }

    public String getActivePath() {
        return activePath;
    }

    public void setActivePath(String activePath) {
        supplied.add(Field.ACTIVE_PATH);
        this.activePath = activePath;
    }

    public Boolean getIsFullPage() {
        return isFullPage;
    }

    public void setIsFullPage(Boolean isFullPage) {
        supplied.add(Field.IS_FULL_PAGE);
        this.isFullPage = isFullPage;
    }
}
