package Cafeteria.superadmin;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class SuperAdminPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String email;
    private String role;
    private String regions;
    private boolean mustChangePassword;

    public SuperAdminPrincipal(String email) {
        this(email, SuperAdminAccounts.ADMIN, "", false);
    }

    public SuperAdminPrincipal(String email, String role, String regions, boolean mustChangePassword) {
        this.email = email;
        this.role = role;
        this.regions = regions;
        this.mustChangePassword = mustChangePassword;
    }

    public String role() {
        return role == null ? SuperAdminAccounts.ADMIN : role;
    }

    public List<String> regions() {
        return regions == null || regions.isBlank() ? List.of() : List.of(regions.split(","));
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (mustChangePassword) {
            return List.of(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN"), new SimpleGrantedAuthority("ROLE_" + role()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
