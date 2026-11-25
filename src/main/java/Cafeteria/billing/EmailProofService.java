package Cafeteria.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import Cafeteria.auth.UserRepository;
import Cafeteria.mail.EmailOutbox;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.shared.ApiException;

@Service
public class EmailProofService {

    static final Duration CODE_TTL = Duration.ofMinutes(15);
    static final Duration PROOF_TTL = Duration.ofMinutes(60);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SENDS_PER_HOUR = 5;

    private final JdbcTemplate jdbc;
    private final EmailOutbox outbox;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public EmailProofService(JdbcTemplate jdbc, EmailOutbox outbox, UserRepository users) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.users = users;
    }

    public void sendCode(String rawEmail) {
        String email = normalize(rawEmail);
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("email_in_use", "Ese correo ya tiene una cuenta. Inicia sesión para renovar tu plan.");
        }
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM payment_email_checks WHERE email = ? AND created_at > now() - interval '1 hour'",
                Integer.class, email);
        if (recent != null && recent >= MAX_SENDS_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Pediste demasiados códigos. Inténtalo más tarde.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("INSERT INTO payment_email_checks (id, email, code_hash, expires_at) VALUES (?, ?, ?, ?)", UUID.randomUUID(), email,
                OnlineOrderService.sha256(code), Timestamp.from(Instant.now().plus(CODE_TTL)));
        outbox.enqueue(EmailTemplates.EMAIL_CODE, email, code);
    }

    public String verify(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.strip();
        List<String[]> found = jdbc.query("UPDATE payment_email_checks SET attempts = attempts + 1 WHERE id = (SELECT id FROM payment_email_checks "
                + "WHERE email = ? AND proof_hash IS NULL AND expires_at > now() ORDER BY created_at DESC LIMIT 1) AND attempts < ? "
                + "RETURNING id, code_hash", (rs, i) -> new String[] { rs.getString(1), rs.getString(2) }, email, MAX_ATTEMPTS);
        if (found.isEmpty()) {
            throw invalid();
        }
        boolean matches = MessageDigest.isEqual(found.get(0)[1].getBytes(StandardCharsets.UTF_8),
                OnlineOrderService.sha256(code).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw invalid();
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE payment_email_checks SET proof_hash = ?, proof_expires_at = ? WHERE id = ?::uuid", OnlineOrderService.sha256(proof),
                Timestamp.from(Instant.now().plus(PROOF_TTL)), found.get(0)[0]);
        return proof;
    }

    public boolean consume(String rawEmail, String proof) {
        if (proof == null || proof.isBlank()) {
            return false;
        }
        return jdbc.update("UPDATE payment_email_checks SET used_at = now() WHERE email = ? AND proof_hash = ? AND used_at IS NULL "
                + "AND proof_expires_at > now()", normalize(rawEmail), OnlineOrderService.sha256(proof)) == 1;
    }

    public void release(String rawEmail, String proof) {
        jdbc.update("UPDATE payment_email_checks SET used_at = NULL WHERE email = ? AND proof_hash = ?", normalize(rawEmail),
                OnlineOrderService.sha256(proof));
    }

    private static String normalize(String email) {
        String clean = email == null ? "" : email.strip().toLowerCase();
        if (!clean.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || clean.length() > 254) {
            throw ApiException.invalidField("email", "Escribe un correo válido");
        }
        return clean;
    }

    private static ApiException invalid() {
        return ApiException.badRequest("invalid_email_code", "El código no es válido o venció. Pide uno nuevo.");
    }
}
