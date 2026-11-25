package com.freesia.system.iam.internal.menu.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequirePermission;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.menu.dto.MenuSaveCommand;
import com.freesia.system.iam.internal.menu.dto.Route;
import com.freesia.system.iam.internal.menu.query.MenuQueries;
import com.freesia.system.iam.internal.menu.service.SaveMenu;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Menu-maintenance endpoint retained from Velox; navigation remains a separate interface. */
@RestController
class MenuController {
    private final MenuQueries menus;
    private final Sessions sessions;
    private final SaveMenu save;

    MenuController(MenuQueries menus, Sessions sessions, SaveMenu save) {
        this.menus = menus;
        this.sessions = sessions;
        this.save = save;
    }

    @RequirePermission("system:menu:query")
    @GetMapping("/api/v3/system/menus")
    R<List<Route>> directory(@RequestHeader("Authorization") String token) {
        return R.ok(menus.directory(sessions.authenticate(token)));
    }

    @RequireAuthenticated
    @PostMapping("/api/v3/system/menus")
    R<UUID> create(@RequestHeader("Authorization") String token, @RequestBody MenuSaveCommand input) {
        return R.ok(save.save(sessions.authenticate(token), null, input, org.slf4j.MDC.get("traceId")));
    }

    @RequireAuthenticated
    @PutMapping("/api/v3/system/menus/{id}")
    R<Boolean> update(@RequestHeader("Authorization") String token, @PathVariable UUID id,
                      @RequestBody MenuSaveCommand input) {
        save.save(sessions.authenticate(token), id, input, org.slf4j.MDC.get("traceId"));
        return R.ok(true);
    }

    @RequireAuthenticated
    @DeleteMapping("/api/v3/system/menus/{id}")
    R<Boolean> delete(@RequestHeader("Authorization") String token, @PathVariable UUID id) {
        return R.ok(save.delete(sessions.authenticate(token), id, org.slf4j.MDC.get("traceId")));
    }
}
