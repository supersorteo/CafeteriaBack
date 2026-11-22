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
import Cafeteria.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/plan")
public class PlanController {

    public record RedeemRequest(@NotBlank @Size(max = 40) String code) {}

    public record PlanResponse(Instant expiresAt, String status, Integer months, boolean qrCustomization) {}

    private static PlanResponse respond(PlanService.Plan plan) {
        return new PlanResponse(plan.expiresAt(), plan.status().name(), plan.months(), plan.qrCustomization());
    }

    private final TenantContext tenantContext;
    private final PlanService plans;
    private final TenantRepository tenants;
    private final UserRepository users;

    public PlanController(TenantContext tenantContext, PlanService plans, TenantRepository tenants, UserRepository users) {
        this.tenantContext = tenantContext;
        this.plans = plans;
        this.tenants = tenants;
        this.users = users;
    }

    @GetMapping
    public PlanResponse current() {
        UUID tenantId = tenantContext.require().tenantId();
        PlanService.Plan plan = PlanService.planOf(tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado")));
        return respond(plan);
    }

    @PostMapping("/redeem")
    public PlanResponse redeem(@Valid @RequestBody RedeemRequest request) {
        TenantContext.Current current = tenantContext.requireOwner();
        User user = users.findById(current.userId()).orElseThrow();
        PlanService.Plan plan = plans.renew(current.tenantId(), request.code(), user.getEmail());
        return respond(plan);
    }
}
