package Cafeteria.site;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.plan.PlanService;
import Cafeteria.security.TenantContext;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/admin/qr")
@Transactional
public class QrStyleController {

    public record QrStyleRequest(
            @NotNull @Pattern(regexp = "square|dots|rounded|classy|classy-rounded|extra-rounded") String dotType,
            @NotNull @Pattern(regexp = "square|dot|extra-rounded") String cornerType,
            @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String dotColor,
            @NotNull @Pattern(regexp = "solid|transparent|gradient") String backgroundMode,
            @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String backgroundColor,
            @NotNull Boolean useLogo) {}

    public record QrStyleResponse(String dotType, String cornerType, String dotColor, String backgroundMode,
            String backgroundColor, boolean useLogo, boolean customizable) {}

    private final TenantContext tenantContext;
    private final TenantRepository tenants;

    public QrStyleController(TenantContext tenantContext, TenantRepository tenants) {
        this.tenantContext = tenantContext;
        this.tenants = tenants;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public QrStyleResponse get() {
        return respond(find(tenantContext.require().tenantId()));
    }

    @PutMapping
    public QrStyleResponse update(@Valid @RequestBody QrStyleRequest request) {
        Tenant tenant = find(tenantContext.requireOwner().tenantId());
        if (!PlanService.planOf(tenant).qrCustomization()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "plan_feature_unavailable",
                    "Personalizar el QR está disponible desde el plan de 3 meses");
        }
        tenant.setQrDotType(request.dotType());
        tenant.setQrCornerType(request.cornerType());
        tenant.setQrDotColor(request.dotColor().toUpperCase());
        tenant.setQrBackgroundMode(request.backgroundMode());
        tenant.setQrBackgroundColor(request.backgroundColor().toUpperCase());
        tenant.setQrUseLogo(request.useLogo());
        return respond(tenants.saveAndFlush(tenant));
    }

    private Tenant find(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    private static QrStyleResponse respond(Tenant t) {
        return new QrStyleResponse(t.getQrDotType(), t.getQrCornerType(), t.getQrDotColor(), t.getQrBackgroundMode(),
                t.getQrBackgroundColor(), t.isQrUseLogo(), PlanService.planOf(t).qrCustomization());
    }
}
