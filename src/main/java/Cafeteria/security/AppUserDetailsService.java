package Cafeteria.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.tenant.TenantUser;
import Cafeteria.tenant.TenantUserRepository;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final TenantUserRepository memberships;

    public AppUserDetailsService(UserRepository users, TenantUserRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        User user = users.findByEmail(username.strip().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
        TenantUser membership = memberships.findByUserId(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
        boolean canLogin = user.isEnabled() && user.getEmailVerifiedAt() != null;
        return new AppPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), membership.getTenantId(),
                membership.getRole(), canLogin);
    }
}
