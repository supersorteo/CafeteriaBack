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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    static final String ATTR_EMAIL = "sa.pending.email";
    static final String ATTR_ACCOUNT = "sa.pending.account";
    static final int MAX_TRIES = 5;
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int IDLE_SECONDS = 15 * 60;
    static final int SLOWDOWN_AFTER_FAILURES = 10;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private final SuperAdminProperties config;
    private final SuperAdminAccounts accounts;
    private final EmailSender mail;
    private final SecureRandom random = new SecureRandom();
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final Deque<Long> failures = new ArrayDeque<>();
    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();
    private long penaltyMillis = 1500;

    public SuperAdminAuthService(SuperAdminProperties config, SuperAdminAccounts accounts, EmailSender mail) {
        this.config = config;
        this.accounts = accounts;
        this.mail = mail;
    }

    public SuperAdminDtos.LoginResponse login(String email, String password, HttpServletRequest request) {
        requireEnabled();
        boolean primary = config.matches(email, password);
        SuperAdminAccounts.Account account = primary ? null : accounts.authenticate(email, password).orElse(null);
        if (!primary && account == null) {
            failAndSlowDown();
            throw invalid();
        }
        String destination = primary ? config.email() : account.email();
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
        session.setAttribute(ATTR_EMAIL, destination);
        session.setAttribute(ATTR_ACCOUNT, account == null ? null : account.id().toString());
        session.setAttribute(ATTR_HASH, hash(saltText, code));
        session.setAttribute(ATTR_EXPIRES, Instant.now(clock).plus(CODE_TTL).toEpochMilli());
        attempts.put(session.getId(), new AtomicInteger());
        session.setMaxInactiveInterval((int) CODE_TTL.toSeconds());
        try {
            mail.send(new EmailMessage(destination, "Tu código de acceso",
                    "Tu código de acceso al panel es: " + code + "\n\nCaduca en 10 minutos. "
                            + (primary ? "Si no fuiste tú, cambia SUPERADMIN_PASSWORD en el servidor."
                                    : "Si no fuiste tú, cambia tu contraseña y avisa al administrador.")));
        } catch (RuntimeException e) {
            session.invalidate();
            log.warn("No se pudo enviar el código de superadmin: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_unavailable",
                    "No pudimos enviar el código. Inténtalo de nuevo en unos minutos.");
        }
        return new SuperAdminDtos.LoginResponse("code", mask(destination), (int) CODE_TTL.toSeconds());
    }

    public void verify(String code, HttpServletRequest request, HttpServletResponse response) {
        requireEnabled();
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(ATTR_HASH) == null) {
            throw invalid();
        }
        String sessionId = session.getId();
        long expires = (Long) session.getAttribute(ATTR_EXPIRES);
        int attempt = attempts.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
        if (Instant.now(clock).toEpochMilli() > expires || attempt > MAX_TRIES) {
            attempts.remove(sessionId);
            session.invalidate();
            throw invalid();
        }
        String expected = (String) session.getAttribute(ATTR_HASH);
        String candidate = hash((String) session.getAttribute(ATTR_SALT), code == null ? "" : code.strip());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) {
            failAndSlowDown();
            if (attempt >= MAX_TRIES) {
                attempts.remove(sessionId);
                session.invalidate();
            }
            throw invalid();
        }
        attempts.remove(sessionId);
        String pendingEmail = (String) session.getAttribute(ATTR_EMAIL);
        String pendingAccount = (String) session.getAttribute(ATTR_ACCOUNT);
        for (String name : new String[] { ATTR_HASH, ATTR_SALT, ATTR_EXPIRES, ATTR_EMAIL, ATTR_ACCOUNT }) {
            session.removeAttribute(name);
        }
        request.changeSessionId();
        SuperAdminPrincipal principal = principalFor(pendingEmail, pendingAccount);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setMaxInactiveInterval(IDLE_SECONDS);
    }

    private SuperAdminPrincipal principalFor(String email, String accountId) {
        if (accountId == null) {
            return new SuperAdminPrincipal(config.email());
        }
        SuperAdminAccounts.Account account = accounts.find(email).orElseThrow(SuperAdminAuthService::invalid);
        if (!account.enabled()) {
            throw invalid();
        }
        accounts.touchLogin(account.id());
        return new SuperAdminPrincipal(account.email(), account.role(), account.regions(), account.mustChangePassword());
    }

    public void changePassword(String current, String next, SuperAdminPrincipal principal, HttpServletRequest request,
            HttpServletResponse response) {
        SuperAdminAccounts.Account account = accounts.find(principal.getUsername()).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "primary_account", "Esta cuenta se administra en el servidor"));
        if (!accounts.passwordMatches(account, current)) {
            failAndSlowDown();
            throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_password", "La contraseña actual no es correcta");
        }
        String candidate = next == null ? "" : next;
        if (candidate.length() < SuperAdminAccounts.MIN_NEW_PASSWORD_LENGTH
                || candidate.equalsIgnoreCase(account.email()) || accounts.passwordMatches(account, candidate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "weak_password",
                    "Usa al menos " + SuperAdminAccounts.MIN_NEW_PASSWORD_LENGTH
                            + " caracteres y una contraseña distinta de la actual");
        }
        accounts.changePassword(account.id(), candidate);
        accounts.audit("SUPERADMIN_PASSWORD_CHANGED", account.id(), account.email(), account.email());
        SuperAdminPrincipal updated = new SuperAdminPrincipal(account.email(), account.role(), account.regions(), false);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
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

    void usePenalty(long millis) {
        this.penaltyMillis = millis;
    }

    void resetLockout() {
        synchronized (failures) {
            failures.clear();
        }
    }

    private void requireEnabled() {
        if (!config.enabled() && !accounts.any()) {
            throw ApiException.notFound("No encontrado");
        }
    }

    private void failAndSlowDown() {
        long now = System.currentTimeMillis();
        int recent;
        synchronized (failures) {
            while (!failures.isEmpty() && now - failures.peekFirst() > FAILURE_WINDOW.toMillis()) {
                failures.pollFirst();
            }
            failures.addLast(now);
            recent = failures.size();
        }
        if (recent > SLOWDOWN_AFTER_FAILURES && penaltyMillis > 0) {
            try {
                Thread.sleep(penaltyMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
