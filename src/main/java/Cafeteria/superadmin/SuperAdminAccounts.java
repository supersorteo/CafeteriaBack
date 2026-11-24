package Cafeteria.superadmin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.shared.ApiException;

@Service
public class SuperAdminAccounts {

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final int MIN_NEW_PASSWORD_LENGTH = 10;

    public record Account(UUID id, String email, String passwordHash, String role, String regions, boolean enabled,
            boolean mustChangePassword) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public SuperAdminAccounts(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public boolean any() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM superadmin_accounts WHERE enabled", Integer.class);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public Optional<Account> find(String email) {
        List<Account> found = jdbc.query("SELECT id, email, password_hash, role, regions, enabled, must_change_password "
                + "FROM superadmin_accounts WHERE email = ?",
                (rs, i) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("role"), rs.getString("regions"), rs.getBoolean("enabled"),
                        rs.getBoolean("must_change_password")),
                normalize(email));
        return found.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Account> authenticate(String email, String password) {
        Optional<Account> account = find(email);
        String candidate = password == null ? "" : password;
        boolean matches = encoder.matches(candidate, account.map(Account::passwordHash).orElse(dummyHash));
        return account.filter(a -> matches && a.enabled());
    }

    @Transactional
    public Account create(String email, String rawPassword, String role, String regions, boolean mustChangePassword) {
        String normalized = normalize(email);
        if (!normalized.contains("@") || rawPassword == null || rawPassword.length() < SuperAdminProperties.MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("invalid_account", "Correo o contraseña no válidos");
        }
        if (!ADMIN.equals(role) && !OPERATOR.equals(role)) {
            throw ApiException.badRequest("invalid_account", "Rol no válido");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO superadmin_accounts (id, email, password_hash, role, regions, must_change_password) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, normalized, encoder.encode(rawPassword), role,
                regions == null ? "" : regions.strip().toUpperCase(), mustChangePassword);
        return find(normalized).orElseThrow();
    }

    @Transactional
    public void changePassword(UUID id, String newPassword) {
        jdbc.update("UPDATE superadmin_accounts SET password_hash = ?, must_change_password = FALSE WHERE id = ?",
                encoder.encode(newPassword), id);
    }

    @Transactional
    public void sync(UUID id, String role, String regions, boolean enabled) {
        jdbc.update("UPDATE superadmin_accounts SET role = ?, regions = ?, enabled = ? WHERE id = ?", role,
                regions == null ? "" : regions.strip().toUpperCase(), enabled, id);
    }

    @Transactional
    public void setEnabled(String email, boolean enabled) {
        jdbc.update("UPDATE superadmin_accounts SET enabled = ? WHERE email = ?", enabled, normalize(email));
    }

    @Transactional
    public void touchLogin(UUID id) {
        jdbc.update("UPDATE superadmin_accounts SET last_login_at = now() WHERE id = ?", id);
    }

    public boolean passwordMatches(Account account, String candidate) {
        return encoder.matches(candidate == null ? "" : candidate, account.passwordHash());
    }

    public void audit(String action, UUID targetId, String label, String actor) {
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, targetId, label, actor);
    }

    static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase();
    }
}
