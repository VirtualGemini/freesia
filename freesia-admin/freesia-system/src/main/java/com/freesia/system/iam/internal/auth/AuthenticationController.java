package com.freesia.system.iam.internal.auth;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.framework.security.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.freesia.common.audit.LoginAudit;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

@RestController
class AuthenticationController {
    private final PasswordLogin login;
    private final Sessions sessions;
    private final AccountQueries accounts;
    private final ClientOrigins origins;
    private final ApplicationEventPublisher events;
    AuthenticationController(PasswordLogin login, Sessions sessions, AccountQueries accounts, ClientOrigins origins, ApplicationEventPublisher events) {
        this.login = login; this.sessions = sessions; this.accounts = accounts;
        this.origins = origins; this.events = events;
    }
    record LoginRequest(@NotBlank(message="{iam.validation.username-required}") @Size(max=50,message="{iam.validation.username-length}") String userName,
                        @NotBlank(message="{iam.validation.password-required}") @Size(max=256,message="{iam.validation.password-length}") String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    @PostMapping("/api/auth/login")
    R<SessionCredentials> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return R.ok(login.login(request.userName(), request.password(), origins.from(http)));
    }
    @GetMapping("/api/auth/access-config")
    R<Map<String,Object>> accessConfig() {
        return R.ok(Map.of("generalRegisterEnabled", false, "forgotPasswordEnabled", false,
                "loginMethods", List.of("password"), "thirdPartyLoginChannels", List.of(), "thirdPartyRegisterChannels", List.of()));
    }
    @RequireAuthenticated
    @GetMapping("/api/account/info")
    R<AccountQueries.AccountInfo> current(@RequestHeader("Authorization") String token) {
        return R.ok(accounts.current(sessions.authenticate(token)));
    }
    @RequireAuthenticated(allowExpiredAccess = true)
    @PostMapping("/api/auth/logout")
    R<Void> logout(@RequestHeader("Authorization") String token, HttpServletRequest http) {
        events.publishEvent(sessions.beginLogout(token, origins.from(http)));
        return R.ok();
    }
    @RequireAuthenticated
    @GetMapping("/api/iam/permissions")
    R<Set<String>> permissions(@RequestHeader("Authorization") String token) {
        return R.ok(accounts.permissionsOf(sessions.authenticate(token)));
    }
    @RequirePermission("system:account:query")
    @GetMapping("/api/role/options")
    R<List<AccountQueries.RoleOption>> roles(@RequestHeader("Authorization") String token) {
        return R.ok(accounts.assignableRoles(sessions.authenticate(token)));
    }
}
