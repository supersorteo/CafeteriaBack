package Cafeteria.billing;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import Cafeteria.auth.User;
import Cafeteria.security.AppPrincipal;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OnlineSessionLogin {

    private final TenantUserRepository memberships;
    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public OnlineSessionLogin(TenantUserRepository memberships) {
        this.memberships = memberships;
    }

    public java.util.UUID login(User user, HttpServletRequest request, HttpServletResponse response) {
        TenantUser membership = memberships.findByUserId(user.getId()).orElseThrow();
        AppPrincipal principal = new AppPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), membership.getTenantId(),
                membership.getRole(), true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        request.getSession(true);
        repository.saveContext(context, request, response);
        return membership.getTenantId();
    }
}
