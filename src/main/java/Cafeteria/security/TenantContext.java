package Cafeteria.security;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.tenant.Role;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;

@Component
public class TenantContext {

    public record Current(UUID userId, UUID tenantId, Role role) {}

    private final UserRepository users;
    private final TenantUserRepository memberships;

    public TenantContext(UserRepository users, TenantUserRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    public Current require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new InsufficientAuthenticationException("Sesión requerida");
        }
        User user = users.findById(principal.getUserId())
                .filter(u -> u.isEnabled() && u.getEmailVerifiedAt() != null)
                .orElseThrow(() -> new InsufficientAuthenticationException("Cuenta no disponible"));
        TenantUser membership = memberships.findByUserId(user.getId())
                .orElseThrow(() -> new InsufficientAuthenticationException("Sin negocio asignado"));
        return new Current(user.getId(), membership.getTenantId(), membership.getRole());
    }

    public Current requireOwner() {
        Current current = require();
        if (current.role() != Role.OWNER) {
            throw new AccessDeniedException("Solo el owner puede realizar esta operación");
        }
        return current;
    }
}
