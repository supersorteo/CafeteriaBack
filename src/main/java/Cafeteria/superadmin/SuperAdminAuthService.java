package Cafeteria.superadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import Cafeteria.mail.EmailMessage;
import Cafeteria.mail.EmailSender;
import Cafeteria.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Service
public class SuperAdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminAuthService.class);

    static final String ATTR_HASH = "sa.code.hash";
    static final String ATTR_SALT = "sa.code.salt";
    static final String ATTR_EXPIRES = "sa.code.expires";
    static final String ATTR_TRIES = "sa.code.tries";
    static final int MAX_TRIES = 5;
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int IDLE_SECONDS = 15 * 60;
    static final int GLOBAL_FAILURES = 10;
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(15);

    private final SuperAdminProperties config;
    private final EmailSender mail;
    private final SecureRandom random = new SecureRandom();
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final Deque<Long> failures = new ArrayDeque<>();
    private Clock clock = Clock.systemUTC();

    public SuperAdminAuthService(SuperAdminProperties config, EmailSender mail) {
        this.config = config;
        this.mail = mail;
    }

    public SuperAdminDtos.LoginResponse login(String email, String password, HttpServletRequest request) {
        requireEnabled();
        requireNotLocked();
        if (!config.matches(email, password)) {
            registerFailure();
            throw invalid();
        }
        SecurityContextHolder.clearContext();
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String saltText = Base64.getEncoder().encodeToString(salt);
        session.setAttribute(ATTR_SALT, saltText);
        session.setAttribute(ATTR_HASH, hash(saltText, code));
        session.setAttribute(ATTR_EXPIRES, Instant.now(clock).plus(CODE_TTL).toEpochMilli());
        session.setAttribute(ATTR_TRIES, 0);
        session.setMaxInactiveInterval((int) CODE_TTL.toSeconds());
        try {
            mail.send(new EmailMessage(config.email(), "Tu código de acceso",
                    "Tu código de acceso al panel es: " + code + "\n\nCaduca en 10 minutos. "
                            + "Si no fuiste tú, cambia SUPERADMIN_PASSWORD en el servidor."));
        } catch (RuntimeException e) {
            session.invalidate();
            log.warn("No se pudo enviar el código de superadmin: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_unavailable",
                    "No pudimos enviar el código. Inténtalo de nuevo en unos minutos.");
        }
        return new SuperAdminDtos.LoginResponse("code", mask(config.email()), (int) CODE_TTL.toSeconds());
    }

    public void verify(String code, HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        requireNotLocked();
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(ATTR_HASH) == null) {
            throw invalid();
        }
        long expires = (Long) session.getAttribute(ATTR_EXPIRES);
        int tries = (Integer) session.getAttribute(ATTR_TRIES);
        if (Instant.now(clock).toEpochMilli() > expires || tries >= MAX_TRIES) {
            session.invalidate();
            throw invalid();
        }
        String expected = (String) session.getAttribute(ATTR_HASH);
        String candidate = hash((String) session.getAttribute(ATTR_SALT), code == null ? "" : code.strip());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) {
            registerFailure();
            if (tries + 1 >= MAX_TRIES) {
                session.invalidate();
            } else {
                session.setAttribute(ATTR_TRIES, tries + 1);
            }
            throw invalid();
        }
        for (String name : new String[] { ATTR_HASH, ATTR_SALT, ATTR_EXPIRES, ATTR_TRIES }) {
            session.removeAttribute(name);
        }
        request.changeSessionId();
        SuperAdminPrincipal principal = new SuperAdminPrincipal(config.email());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setMaxInactiveInterval(IDLE_SECONDS);
    }

    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    void resetLockout() {
        synchronized (failures) {
            failures.clear();
        }
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw ApiException.notFound("No encontrado");
        }
    }

    private void requireNotLocked() {
        long now = System.currentTimeMillis();
        synchronized (failures) {
            while (!failures.isEmpty() && now - failures.peekFirst() > GLOBAL_WINDOW.toMillis()) {
                failures.pollFirst();
            }
            if (failures.size() >= GLOBAL_FAILURES) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                        "Demasiados intentos, inténtalo más tarde");
            }
        }
    }

    private void registerFailure() {
        synchronized (failures) {
            failures.addLast(System.currentTimeMillis());
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Credenciales inválidas");
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}
