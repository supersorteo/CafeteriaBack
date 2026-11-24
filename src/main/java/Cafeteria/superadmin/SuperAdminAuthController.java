package Cafeteria.superadmin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.superadmin.SuperAdminDtos.ChangePasswordRequest;
import Cafeteria.superadmin.SuperAdminDtos.CodeRequest;
import Cafeteria.superadmin.SuperAdminDtos.LoginRequest;
import Cafeteria.superadmin.SuperAdminDtos.LoginResponse;
import Cafeteria.superadmin.SuperAdminDtos.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/superadmin/auth")
public class SuperAdminAuthController {

    private final SuperAdminAuthService auth;
    private final SuperAdminAccounts accounts;
    private final SuperAdminScopes scopes;

    public SuperAdminAuthController(SuperAdminAuthService auth, SuperAdminAccounts accounts, SuperAdminScopes scopes) {
        this.auth = auth;
        this.accounts = accounts;
        this.scopes = scopes;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return auth.login(body.email(), body.password(), request);
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        auth.verify(body.code(), request, response);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        if (authentication.getPrincipal() instanceof SuperAdminPrincipal principal) {
            return new MeResponse(principal.getUsername(), principal.role(), principal.regions(), principal.mustChangePassword(),
                    accounts.find(principal.getUsername()).isPresent(), scopes.viewer(authentication, null).owner());
        }
        return new MeResponse(authentication.getName(), SuperAdminAccounts.ADMIN, java.util.List.of(), false, false,
                scopes.viewer(authentication, null).owner());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        auth.changePassword(body.current(), body.next(), (SuperAdminPrincipal) authentication.getPrincipal(), request, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        auth.logout(request);
    }
}
