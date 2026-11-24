package Cafeteria.coupon;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.coupon.CouponService.Coupon;
import Cafeteria.security.TenantContext;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import Cafeteria.plan.Entitlements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/coupons")
public class AdminCouponController {

    public record CouponRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,24}$", message = "Usa de 3 a 24 letras, números, guiones o guion bajo") String code,
            @NotNull @Pattern(regexp = "DESCUENTO|VIGENCIA|CAMBIO|REGALO|OTRO") String type,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 180) String benefit,
            @Size(max = 80) String validityText,
            LocalDate startsOn,
            LocalDate endsOn,
            @Pattern(regexp = "^[1-7]{1,7}$") String weekdays,
            LocalTime timeFrom,
            LocalTime timeTo,
            @Min(1) @Max(1000000) Integer maxRedemptions,
            Boolean featured,
            Boolean active,
            Long version) {}

    public record CouponResponse(UUID id, String code, String type, String title, String benefit, String validityText,
            LocalDate startsOn, LocalDate endsOn, String weekdays, LocalTime timeFrom, LocalTime timeTo,
            Integer maxRedemptions, int redemptions, boolean featured, boolean active, long version, String status,
            boolean counted) {}

    private final TenantContext tenantContext;
    private final CouponService coupons;
    private final TenantRepository tenants;

    public AdminCouponController(TenantContext tenantContext, CouponService coupons, TenantRepository tenants) {
        this.tenantContext = tenantContext;
        this.coupons = coupons;
        this.tenants = tenants;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CouponResponse> list() {
        UUID tenantId = tenantContext.require().tenantId();
        LocalDate today = Entitlements.today(tenant(tenantId));
        return coupons.list(tenantId).stream().map(c -> respond(c, today)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CouponResponse create(@Valid @RequestBody CouponRequest request) {
        UUID tenantId = tenantContext.require().tenantId();
        return respond(coupons.create(tenantId, input(request)), Entitlements.today(tenant(tenantId)));
    }

    @PutMapping("/{id}")
    public CouponResponse update(@PathVariable UUID id, @Valid @RequestBody CouponRequest request) {
        UUID tenantId = tenantContext.require().tenantId();
        return respond(coupons.update(tenantId, id, input(request)), Entitlements.today(tenant(tenantId)));
    }

    @PostMapping("/{id}/redeem")
    public CouponResponse redeem(@PathVariable UUID id) {
        UUID tenantId = tenantContext.require().tenantId();
        return respond(coupons.redeem(tenantId, id), Entitlements.today(tenant(tenantId)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        coupons.delete(tenantContext.require().tenantId(), id);
    }

    private Tenant tenant(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    private static CouponService.Input input(CouponRequest r) {
        return new CouponService.Input(r.code(), r.type(), r.title(), r.benefit(), r.validityText(), r.startsOn(), r.endsOn(),
                r.weekdays(), r.timeFrom(), r.timeTo(), r.maxRedemptions(), r.featured(), r.active(), r.version());
    }

    private static CouponResponse respond(Coupon c, LocalDate today) {
        String status = CouponService.status(c, today);
        return new CouponResponse(c.id(), c.code(), c.type(), c.title(), c.benefit(), c.validityText(), c.startsOn(), c.endsOn(),
                c.weekdays(), c.timeFrom(), c.timeTo(), c.maxRedemptions(), c.redemptions(), c.featured(), c.active(),
                c.version(), status, "ACTIVE".equals(status));
    }
}
