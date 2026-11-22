package com.freesia.system.iam.internal.menu.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.menu.dto.Route;
import com.freesia.system.iam.internal.menu.query.NavigationQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class NavigationController {
    private final NavigationQueries navigation;
    private final Sessions sessions;

    NavigationController(NavigationQueries navigation, Sessions sessions) {
        this.navigation = navigation;
        this.sessions = sessions;
    }

    @RequireAuthenticated
    @GetMapping("/api/v3/system/menus/simple")
    R<List<Route>> navigation(@RequestHeader("Authorization") String token) {
        return R.ok(navigation.navigation(sessions.authenticate(token)));
    }
}
