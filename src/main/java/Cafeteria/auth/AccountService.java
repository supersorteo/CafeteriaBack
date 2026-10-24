package Cafeteria.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.mail.EmailOutbox;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.shared.ApiException;

@Service
@Transactional
public class AccountService {

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofHours(1);
    static final int COOLDOWN_SECONDS = 60;

    private final UserRepository users;
    private final TokenService tokens;
    private final EmailOutbox outbox;
    private final PasswordEncoder encoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountService(UserRepository users, TokenService tokens, EmailOutbox outbox, PasswordEncoder encoder,
            FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.users = users;
        this.tokens = tokens;
        this.outbox = outbox;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    public void issueVerification(User user) {
        if (tokens.issuedRecently(user.getId(), TokenService.VERIFY_EMAIL, COOLDOWN_SECONDS)) {
            return;
        }
        String token = tokens.issue(user.getId(), TokenService.VERIFY_EMAIL, VERIFY_TTL);
        outbox.enqueue(EmailTemplates.VERIFY_EMAIL, user.getEmail(), token);
    }

    public void notifyAccountExists(User user) {
        if (!outbox.recentlyQueued(EmailTemplates.ACCOUNT_EXISTS, user.getEmail(), COOLDOWN_SECONDS)) {
            outbox.enqueue(EmailTemplates.ACCOUNT_EXISTS, user.getEmail(), null);
        }
    }

    public void verifyEmail(String token) {
        UUID userId = tokens.consume(TokenService.VERIFY_EMAIL, token).orElseThrow(AccountService::invalidToken);
        User user = users.findById(userId).filter(User::isEnabled).orElseThrow(AccountService::invalidToken);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            users.save(user);
        }
    }

    public void resendVerification(String email) {
        users.findByEmail(normalize(email))
                .filter(u -> u.isEnabled() && u.getEmailVerifiedAt() == null)
                .ifPresent(this::issueVerification);
    }

    public void forgotPassword(String email) {
        users.findByEmail(normalize(email)).filter(User::isEnabled).ifPresent(user -> {
            if (user.getEmailVerifiedAt() == null) {
                issueVerification(user);
                return;
            }
            if (tokens.issuedRecently(user.getId(), TokenService.RESET_PASSWORD, COOLDOWN_SECONDS)) {
                return;
            }
            String token = tokens.issue(user.getId(), TokenService.RESET_PASSWORD, RESET_TTL);
            outbox.enqueue(EmailTemplates.RESET_PASSWORD, user.getEmail(), token);
        });
    }

    public void resetPassword(String token, String newPassword) {
        UUID userId = tokens.consume(TokenService.RESET_PASSWORD, token).orElseThrow(AccountService::invalidToken);
        User user = users.findById(userId).filter(User::isEnabled).orElseThrow(AccountService::invalidToken);
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);
        tokens.revokeAll(userId);
        sessions.findByPrincipalName(user.getEmail()).keySet().forEach(sessions::deleteById);
    }

    static String normalize(String email) {
        return email.strip().toLowerCase();
    }

    private static ApiException invalidToken() {
        return ApiException.badRequest("invalid_token", "El enlace no es válido o ha caducado");
    }
}
