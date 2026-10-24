package Cafeteria.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    public static final String RESET_PASSWORD = "RESET_PASSWORD";

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(UUID userId, String purpose, Duration ttl) {
        jdbc.update("UPDATE account_tokens SET used_at = now() WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
                userId, purpose);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO account_tokens (id, user_id, purpose, token_hash, expires_at) "
                + "VALUES (?,?,?,?, now() + make_interval(secs => ?::double precision))",
                UUID.randomUUID(), userId, purpose, hash(raw), ttl.toSeconds());
        return raw;
    }

    public Optional<UUID> consume(String purpose, String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return Optional.empty();
        }
        List<UUID> users = jdbc.query("UPDATE account_tokens SET used_at = now() WHERE token_hash = ? "
                + "AND purpose = ? AND used_at IS NULL AND expires_at > now() RETURNING user_id",
                (rs, i) -> rs.getObject("user_id", UUID.class), hash(rawToken), purpose);
        return users.stream().findFirst();
    }

    public boolean issuedRecently(UUID userId, String purpose, int seconds) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE user_id = ? AND purpose = ? "
                + "AND created_at > now() - make_interval(secs => ?::double precision)",
                Integer.class, userId, purpose, seconds);
        return count != null && count > 0;
    }

    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE account_tokens SET used_at = now() WHERE user_id = ? AND used_at IS NULL", userId);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
