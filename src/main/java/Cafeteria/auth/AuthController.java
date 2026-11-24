package Cafeteria.auth;

import java.util.UUID;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.security.TenantContext;
import Cafeteria.site.SiteService;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record CsrfResponse(String headerName) {}

    public record TenantSummary(UUID id, String slug, String name, boolean published, String logoUrl) {}

    public record PlanSummary(java.time.Instant expiresAt, String status, Integer months, boolean qrCustomization,
            String planCode, Cafeteria.plan.Entitlements.Limits limits, Cafeteria.plan.Entitlements.Usage usage) {}

    public record QrSummary(String dotType, String cornerType, String dotColor, String backgroundMode,
            String backgroundColor, boolean useLogo) {}

    public record MeResponse(String email, Role role, TenantSummary tenant, PlanSummary plan, QrSummary qr) {}

    private final TenantContext tenantContext;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final SiteService site;
    private final Cafeteria.plan.Entitlements entitlements;

    public AuthController(TenantContext tenantContext, UserRepository users, TenantRepository tenants, SiteService site,
            Cafeteria.plan.Entitlements entitlements) {
        this.entitlements = entitlements;
        this.tenantContext = tenantContext;
        this.users = users;
        this.tenants = tenants;
        this.site = site;
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
                new TenantSummary(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.isPublished(),
                        site.logoUrl(tenant.getId())),
                planSummary(tenant), qrSummary(tenant));
    }

    private static QrSummary qrSummary(Tenant t) {
        return new QrSummary(t.getQrDotType(), t.getQrCornerType(), t.getQrDotColor(), t.getQrBackgroundMode(),
                t.getQrBackgroundColor(), t.isQrUseLogo());
    }

    private PlanSummary planSummary(Tenant tenant) {
        Cafeteria.plan.PlanService.Plan plan = Cafeteria.plan.PlanService.planOf(tenant);
        return new PlanSummary(plan.expiresAt(), plan.status().name(), plan.months(), plan.qrCustomization(), plan.planCode(),
                entitlements.limitsOf(tenant), entitlements.usageOf(tenant));
    }
}
