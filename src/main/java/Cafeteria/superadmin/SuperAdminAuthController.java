package Cafeteria.superadmin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    public SuperAdminAuthController(SuperAdminAuthService auth) {
        this.auth = auth;
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
        return new MeResponse(authentication.getName());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        auth.logout(request);
    }
}
