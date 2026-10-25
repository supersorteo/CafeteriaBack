package Cafeteria.superadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminProperties {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final String email;
    private final String passwordHash;
    private final String dummyHash;
    private final PasswordEncoder encoder;

    public SuperAdminProperties(@Value("${superadmin.email:}") String email,
            @Value("${superadmin.password:}") String password, PasswordEncoder encoder) {
        this.encoder = encoder;
        String normalized = email == null ? "" : email.strip().toLowerCase();
        boolean anyEmail = !normalized.isEmpty();
        boolean anyPassword = password != null && !password.isBlank();
        if (!anyEmail && !anyPassword) {
            this.email = null;
            this.passwordHash = null;
            this.dummyHash = null;
            return;
        }
        if (!anyEmail || !anyPassword || !normalized.contains("@") || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("SUPERADMIN_EMAIL debe ser un correo y SUPERADMIN_PASSWORD tener al menos "
                    + MIN_PASSWORD_LENGTH + " caracteres");
        }
        this.email = normalized;
        this.passwordHash = encoder.encode(password);
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public boolean enabled() {
        return email != null;
    }

    public String email() {
        return email;
    }

    public boolean matches(String candidateEmail, String candidatePassword) {
        if (!enabled()) {
            return false;
        }
        String normalized = candidateEmail == null ? "" : candidateEmail.strip().toLowerCase();
        boolean emailOk = MessageDigest.isEqual(normalized.getBytes(StandardCharsets.UTF_8),
                email.getBytes(StandardCharsets.UTF_8));
        boolean passwordOk = encoder.matches(candidatePassword == null ? "" : candidatePassword,
                emailOk ? passwordHash : dummyHash);
        return emailOk && passwordOk;
    }
}
