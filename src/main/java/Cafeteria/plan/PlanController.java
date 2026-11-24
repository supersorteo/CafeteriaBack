package Cafeteria.plan;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.security.TenantContext;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/plan")
public class PlanController {

    public record RedeemRequest(@NotBlank @Size(max = 40) String code) {}

    public record PlanResponse(Instant expiresAt, String status, Integer months, boolean qrCustomization, String planCode,
            Entitlements.Limits limits, Entitlements.Usage usage) {}

    private PlanResponse respond(Tenant tenant) {
        PlanService.Plan plan = PlanService.planOf(tenant);
        return new PlanResponse(plan.expiresAt(), plan.status().name(), plan.months(), plan.qrCustomization(), plan.planCode(),
                entitlements.limitsOf(tenant), entitlements.usageOf(tenant));
    }

    private final TenantContext tenantContext;
    private final PlanService plans;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final Entitlements entitlements;

    public PlanController(TenantContext tenantContext, PlanService plans, TenantRepository tenants, UserRepository users,
            Entitlements entitlements) {
        this.entitlements = entitlements;
        this.tenantContext = tenantContext;
        this.plans = plans;
        this.tenants = tenants;
        this.users = users;
    }

    @GetMapping
    public PlanResponse current() {
        UUID tenantId = tenantContext.require().tenantId();
        return respond(tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado")));
    }

    @PostMapping("/redeem")
    public PlanResponse redeem(@Valid @RequestBody RedeemRequest request) {
        TenantContext.Current current = tenantContext.requireOwner();
        User user = users.findById(current.userId()).orElseThrow();
        plans.renew(current.tenantId(), request.code(), user.getEmail());
        return respond(tenants.findById(current.tenantId()).orElseThrow(() -> ApiException.notFound("Negocio no encontrado")));
    }
}
