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

    static final String PLAN_PATHS = "/api/v1/admin/plan/";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final TenantRepository tenants;

    public PlanGuardInterceptor(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITE_METHODS.contains(request.getMethod()) || request.getRequestURI().startsWith(PLAN_PATHS)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal principal) {
            tenants.findById(principal.getTenantId()).ifPresent(tenant -> {
                if (PlanService.planOf(tenant).status().locked()) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "plan_expired",
                            "Tu plan venció. Renuévalo para volver a hacer cambios.");
                }
            });
        }
        return true;
    }
}
