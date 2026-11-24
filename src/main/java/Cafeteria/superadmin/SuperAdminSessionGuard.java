package Cafeteria.superadmin;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import Cafeteria.shared.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class SuperAdminSessionGuard extends OncePerRequestFilter {

    private final SuperAdminAccounts accounts;

    public SuperAdminSessionGuard(SuperAdminAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SuperAdminPrincipal principal) {
            boolean revoked = accounts.find(principal.getUsername())
                    .map(account -> !account.enabled()
                            || !account.role().equals(principal.role())
                            || !java.util.Objects.equals(account.regions() == null ? "" : account.regions(), String.join(",", principal.regions()))
                            || account.mustChangePassword() != principal.mustChangePassword())
                    .orElse(false);
            if (revoked) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Sesión requerida");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
