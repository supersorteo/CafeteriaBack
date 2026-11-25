package Cafeteria.billing;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import Cafeteria.security.ClientIp;
import Cafeteria.shared.ApiException;
import Cafeteria.superadmin.SuperAdminScopes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/superadmin")
public class SuperAdminBillingController {

    public record PriceRequest(@NotBlank String planCode, @NotNull Integer months, @NotNull @Min(100) @Max(100000) Integer usdCents) {}

    private final BillingService billing;
    private final SuperAdminScopes scopes;
    private final boolean trustForwardedFor;

    public SuperAdminBillingController(BillingService billing, SuperAdminScopes scopes,
            @Value("${security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.billing = billing;
        this.scopes = scopes;
        this.trustForwardedFor = trustForwardedFor;
    }

    @GetMapping("/orders")
    public BillingService.OrderPage orders(@RequestParam(required = false) String status,
            @RequestParam(required = false) String query, @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return billing.list(scopes.viewer(auth, scope), admin, status, query, page, size);
    }

    @PostMapping("/orders/{id}/confirm")
    public BillingService.OrderRow confirm(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        return billing.confirm(scopes.viewer(auth, null), id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @PostMapping("/orders/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        billing.cancel(scopes.viewer(auth, null), id, auth.getName(), ClientIp.resolve(request, trustForwardedFor));
    }

    @GetMapping("/billing/options")
    public BillingService.Options options() {
        return billing.options();
    }

    @PutMapping("/billing/prices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void price(@Valid @RequestBody PriceRequest body, Authentication auth, HttpServletRequest request) {
        if (!scopes.viewer(auth, null).owner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Solo el dueño puede cambiar los precios");
        }
        billing.setPrice(body.planCode(), body.months(), body.usdCents(), auth.getName(),
                ClientIp.resolve(request, trustForwardedFor));
    }
}
