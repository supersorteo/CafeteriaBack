package Cafeteria.plan;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import Cafeteria.security.AppPrincipal;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class PlanGuardInterceptor implements HandlerInterceptor {

    static final String REDEEM_PATH = "/api/v1/admin/plan/redeem";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final TenantRepository tenants;

    public PlanGuardInterceptor(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITE_METHODS.contains(request.getMethod()) || REDEEM_PATH.equals(request.getRequestURI())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal principal) {
            tenants.findById(principal.getTenantId()).ifPresent(tenant -> {
                if (PlanService.planOf(tenant).status() == PlanService.Status.EXPIRED) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "plan_expired",
                            "Tu plan venció. Canjea un código nuevo para volver a hacer cambios.");
                }
            });
        }
        return true;
    }
}
