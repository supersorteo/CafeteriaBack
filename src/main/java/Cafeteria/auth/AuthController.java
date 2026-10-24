package Cafeteria.auth;

import java.util.UUID;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.security.TenantContext;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record CsrfResponse(String headerName) {}

    public record TenantSummary(UUID id, String slug, String name, boolean published) {}

    public record MeResponse(String email, Role role, TenantSummary tenant) {}

    private final TenantContext tenantContext;
    private final UserRepository users;
    private final TenantRepository tenants;

    public AuthController(TenantContext tenantContext, UserRepository users, TenantRepository tenants) {
        this.tenantContext = tenantContext;
        this.users = users;
        this.tenants = tenants;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        token.getToken();
        return new CsrfResponse("X-XSRF-TOKEN");
    }

    @GetMapping("/me")
    public MeResponse me() {
        TenantContext.Current current = tenantContext.require();
        User user = users.findById(current.userId()).orElseThrow();
        Tenant tenant = tenants.findById(current.tenantId()).orElseThrow();
        return new MeResponse(user.getEmail(), current.role(),
                new TenantSummary(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.isPublished()));
    }
}
