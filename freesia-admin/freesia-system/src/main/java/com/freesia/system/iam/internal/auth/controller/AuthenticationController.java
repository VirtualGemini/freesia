package com.freesia.system.iam.internal.auth.controller;

import com.freesia.common.result.R;
import com.freesia.system.iam.internal.auth.dto.LoginRequest;
import com.freesia.system.iam.internal.auth.service.PasswordLogin;
import com.freesia.system.iam.internal.origin.service.ClientOrigins;
import com.freesia.system.iam.internal.session.dto.SessionCredentials;
import com.freesia.system.iam.internal.session.service.Sessions;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
class AuthenticationController {
    private final PasswordLogin login;
    private final Sessions sessions;
    private final ClientOrigins origins;
    private final ApplicationEventPublisher events;
    private final BrowserSessions browser;
    AuthenticationController(PasswordLogin login, Sessions sessions, ClientOrigins origins, ApplicationEventPublisher events,
                             BrowserSessions browser) {
        this.login = login; this.sessions = sessions;
        this.origins = origins; this.events = events; this.browser = browser;
    }
    @PostMapping("/api/auth/login")
    R<SessionCredentials.Access> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        return R.ok(browser.install(login.login(request.userName(), request.password(), origins.from(http)), response));
    }
    @PostMapping("/api/auth/refresh")
    R<SessionCredentials.Access> refresh(HttpServletRequest http, HttpServletResponse response) {
        return R.ok(browser.install(sessions.refresh(browser.refresh(http), browser.expectedSession(http)), response));
    }
    @GetMapping("/api/auth/access-config")
    R<Map<String,Object>> accessConfig() {
        return R.ok(Map.of("generalRegisterEnabled", false, "forgotPasswordEnabled", false,
                "loginMethods", List.of("password"), "thirdPartyLoginChannels", List.of(), "thirdPartyRegisterChannels", List.of()));
    }
    @PostMapping("/api/auth/logout")
    R<Void> logout(@RequestHeader(value = "Authorization", required = false) String token, HttpServletRequest http,
                   HttpServletResponse response) {
        var logout = sessions.beginLogout(token, browser.refresh(http), browser.expectedSession(http), origins.from(http));
        events.publishEvent(logout.audit());
        if (logout.clearCookie()) browser.clear(response);
        return R.ok();
    }
}
