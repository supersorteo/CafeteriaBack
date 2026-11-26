package Cafeteria.billing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import Cafeteria.auth.RegistrationService;
import Cafeteria.auth.User;
import Cafeteria.auth.UserRepository;
import Cafeteria.mail.EmailOutbox;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.plan.PlanService;
import Cafeteria.shared.ApiException;
import Cafeteria.superadmin.SuperAdminProperties;
import Cafeteria.tenant.SlugRules;
import Cafeteria.tenant.TenantRepository;

@Service
public class OnlineOrderService {

    private static final Logger log = LoggerFactory.getLogger(OnlineOrderService.class);
    private static final Duration CLAIM_WINDOW = Duration.ofMinutes(30);

    public record CheckoutRequest(String planCode, String region, String method, String email, String businessName,
            String slug, String password, String emailProof) {}

    public record Checkout(String reference, String claimToken, String method, String providerRef, String initPoint,
            String amountLabel) {}

    public record State(String reference, String status, String method, boolean ready, String failure, boolean renewal) {}

    private record Priced(String plan, String planName, int months, int usdCents, String currency, int amountMinor, BigDecimal rate) {}

    record Order(UUID id, String reference, String planCode, String planName, int months, String region, String method,
            String email, int usdCents, String currency, int amountMinor, String status, String providerRef, String businessName,
            String slug, String passwordHash, String claimHash, UUID tenantId, Instant paidAt, Instant claimUsedAt, boolean emailVerified,
            Instant fulfilledAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentsProperties config;
    private final BillingService billing;
    private final PayPalClient paypal;
    private final MercadoPagoClient mercadopago;
    private final FxRates fx;
    private final PasswordEncoder encoder;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final RegistrationService registration;
    private final PlanService plans;
    private final EmailOutbox outbox;
    private final SuperAdminProperties superAdmin;
    private final EmailProofService proofs;
    private final SecureRandom random = new SecureRandom();

    public OnlineOrderService(JdbcTemplate jdbc, TransactionTemplate tx, PaymentsProperties config, BillingService billing,
            PayPalClient paypal, MercadoPagoClient mercadopago, FxRates fx, PasswordEncoder encoder, UserRepository users,
            TenantRepository tenants, RegistrationService registration, PlanService plans, EmailOutbox outbox,
            SuperAdminProperties superAdmin, EmailProofService proofs) {
        this.proofs = proofs;
        this.jdbc = jdbc;
        this.tx = tx;
        this.config = config;
        this.billing = billing;
        this.paypal = paypal;
        this.mercadopago = mercadopago;
        this.fx = fx;
        this.encoder = encoder;
        this.users = users;
        this.tenants = tenants;
        this.registration = registration;
        this.plans = plans;
        this.outbox = outbox;
        this.superAdmin = superAdmin;
    }

    public Checkout createCheckout(CheckoutRequest request) {
        String method = upper(request.method());
        Priced price = price(request.planCode(), request.region(), method);
        String region = upper(request.region());
        String email = request.email() == null ? "" : request.email().strip().toLowerCase();
        String slug = request.slug() == null ? "" : request.slug().strip().toLowerCase();
        String businessName = request.businessName() == null ? "" : request.businessName().strip();
        validate(email, slug, businessName, request.password());
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("email_in_use", "Ese correo ya tiene una cuenta. Inicia sesión para renovar tu plan.");
        }
        if (tenants.existsBySlug(slug)) {
            throw ApiException.conflict("slug_taken", "Ese identificador ya está en uso");
        }
        if (!proofs.consume(email, request.emailProof())) {
            throw ApiException.badRequest("email_not_verified", "Confirma tu correo con el código que te enviamos.");
        }
        jdbc.update("UPDATE orders SET status = 'EXPIRED', failure_reason = 'Reemplazado por un pedido nuevo del mismo comprador' "
                + "WHERE status = 'PENDING' AND slug IS NOT NULL AND buyer_email = ?", email);
        String reference = billing.newReference();
        String claim = token();
        Instant expires = Instant.now().plus(Duration.ofMinutes(config.checkoutMinutes()));
        try {
            jdbc.update("INSERT INTO orders (id, reference, plan_code, months, region, method, buyer_email, usd_cents, expires_at, currency, "
                    + "amount_minor, fx_rate, business_name, slug, password_hash, claim_hash, email_verified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)",
                    UUID.randomUUID(), reference, price.plan(), price.months(), region, method, email, price.usdCents(), Timestamp.from(expires),
                    price.currency(), price.amountMinor(), price.rate(), businessName, slug, encoder.encode(request.password()), sha256(claim));
        } catch (DuplicateKeyException e) {
            proofs.release(email, request.emailProof());
            throw ApiException.conflict("slug_taken", "Ese identificador ya está en uso");
        }
        try {
            return start(reference, claim, method, price, email, expires);
        } catch (ApiException e) {
            proofs.release(email, request.emailProof());
            throw e;
        }
    }

    public Checkout createRenewal(UUID tenantId, String ownerEmail, String planCode, String rawRegion, String rawMethod) {
        String method = upper(rawMethod);
        Priced price = price(planCode, rawRegion, method);
        String slug = tenants.findById(tenantId).map(t -> t.getSlug()).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        if (Cafeteria.seed.DemoSiteSeeder.SLUG.equals(slug)) {
            throw ApiException.conflict("plan_not_renewable", "El sitio de demostración no necesita renovación");
        }
        jdbc.update("UPDATE orders SET status = 'EXPIRED', failure_reason = 'Reemplazado por una renovación nueva' "
                + "WHERE status = 'PENDING' AND slug IS NULL AND tenant_id = ?", tenantId);
        String reference = billing.newReference();
        Instant expires = Instant.now().plus(Duration.ofMinutes(config.checkoutMinutes()));
        jdbc.update("INSERT INTO orders (id, reference, plan_code, months, region, method, buyer_email, usd_cents, expires_at, currency, "
                + "amount_minor, fx_rate, tenant_id, email_verified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)",
                UUID.randomUUID(), reference, price.plan(), price.months(), upper(rawRegion), method, ownerEmail.toLowerCase(), price.usdCents(),
                Timestamp.from(expires), price.currency(), price.amountMinor(), price.rate(), tenantId);
        return start(reference, null, method, price, ownerEmail, expires);
    }

    private Priced price(String rawPlan, String rawRegion, String method) {
        String plan = upper(rawPlan);
        String region = upper(rawRegion);
        BillingService.Options options = billing.options();
        BillingService.PlanOption option = options.plans().stream().filter(p -> p.code().equals(plan)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "Elige un plan disponible"));
        BillingService.RegionOption zone = options.regions().stream().filter(r -> r.code().equals(region)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("region", "Elige tu país"));
        if (zone.methods().stream().noneMatch(m -> m.code().equals(method) && m.available() && !BillingService.WHATSAPP.equals(method))) {
            throw ApiException.conflict("method_unavailable", "Este método de pago no está disponible por ahora");
        }
        if (!BillingService.MERCADOPAGO.equals(method)) {
            return new Priced(plan, option.name(), option.months(), option.usdCents(), "USD", option.usdCents(), null);
        }
        BigDecimal rate = fx.usdToArs().orElseThrow(() -> ApiException.conflict("method_unavailable", "Este método de pago no está disponible por ahora"));
        return new Priced(plan, option.name(), option.months(), option.usdCents(), "ARS", FxRates.pesos(option.usdCents(), rate) * 100, rate);
    }

    private Checkout start(String reference, String claim, String method, Priced price, String email, Instant expires) {
        String title = "Deliora " + price.planName() + " (1 mes)";
        try {
            if (BillingService.PAYPAL.equals(method)) {
                String id = paypal.createOrder(reference, title, price.usdCents());
                jdbc.update("UPDATE orders SET provider_ref = ? WHERE reference = ?", id, reference);
                return new Checkout(reference, claim, method, id, null, "US$ " + BillingService.moneyLabel(price.usdCents()));
            }
            MercadoPagoClient.Preference preference = mercadopago.createPreference(reference, price.plan(), title,
                    BigDecimal.valueOf(price.amountMinor(), 2), email, expires);
            jdbc.update("UPDATE orders SET provider_ref = ? WHERE reference = ?", preference.id(), reference);
            return new Checkout(reference, claim, method, preference.id(), preference.initPoint(), "$ " + (price.amountMinor() / 100) + " ARS");
        } catch (PaymentProviderException e) {
            log.error("No se pudo iniciar el cobro {} ({}): {}", reference, method, e.getMessage());
            jdbc.update("UPDATE orders SET status = 'FAILED', failure_reason = ? WHERE reference = ?", abbreviate(e.getMessage()), reference);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "payment_provider_error",
                    "No pudimos iniciar el pago. Inténtalo de nuevo en unos minutos.");
        }
    }

    public State capturePayPal(String reference) {
        Order order = find(reference);
        requireMethod(order, BillingService.PAYPAL);
        if (!"PAID".equals(order.status()) && order.providerRef() != null) {
            try {
                PayPalClient.Capture capture = paypal.capture(order.providerRef(), reference);
                processPayPal(order, capture);
            } catch (PaymentProviderException e) {
                log.warn("Captura de PayPal fallida para {}: {}", reference, e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "payment_provider_error", "No pudimos confirmar el pago con PayPal.");
            }
        }
        return state(reference);
    }

    public State verifyMercadoPago(String reference, String paymentId) {
        Order order = find(reference);
        requireMethod(order, BillingService.MERCADOPAGO);
        if (!"PAID".equals(order.status()) && paymentId != null && !paymentId.isBlank()) {
            try {
                processMercadoPago(order, mercadopago.payment(paymentId.strip()));
            } catch (PaymentProviderException e) {
                log.warn("Verificacion de Mercado Pago fallida para {}: {}", reference, e.getMessage());
            }
        }
        return state(reference);
    }

    public boolean handleMercadoPagoNotification(String signature, String requestId, String dataId, String type) {
        if (!mercadopago.validSignature(signature, requestId, dataId, Instant.now())) {
            return false;
        }
        if (!"payment".equals(type) || dataId == null) {
            return true;
        }
        if (!newEvent("MERCADOPAGO", "req:" + requestId + ":" + dataId, null, "payment")) {
            return true;
        }
        try {
            MercadoPagoClient.Payment payment = mercadopago.payment(dataId);
            if (!payment.externalReference().isEmpty()) {
                processMercadoPago(find(payment.externalReference()), payment);
            }
        } catch (PaymentProviderException | ApiException e) {
            log.warn("Notificacion de Mercado Pago {} no procesada: {}", dataId, e.getMessage());
        }
        return true;
    }

    public State state(String reference) {
        Order order = find(reference);
        String failure = switch (order.status()) {
            case "FAILED" -> "El pago no se pudo completar.";
            case "EXPIRED" -> "El pedido venció.";
            default -> null;
        };
        return new State(order.reference(), order.status(), order.method(), order.fulfilledAt() != null, failure, order.slug() == null);
    }

    public Optional<User> claim(String reference, String claimToken) {
        Order order = find(reference);
        if (order.claimHash() == null || claimToken == null || order.tenantId() == null || order.paidAt() == null
                || !MessageDigest.isEqual(order.claimHash().getBytes(StandardCharsets.UTF_8), sha256(claimToken).getBytes(StandardCharsets.UTF_8))
                || Instant.now().isAfter(order.paidAt().plus(CLAIM_WINDOW))) {
            return Optional.empty();
        }
        Integer used = jdbc.update("UPDATE orders SET claim_used_at = now() WHERE id = ? AND claim_used_at IS NULL", order.id());
        return used == 1 ? users.findByEmail(order.email()) : Optional.empty();
    }

    public void reconcile() {
        for (UUID id : jdbc.query("SELECT id FROM orders WHERE status = 'PAID' AND fulfilled_at IS NULL AND method IN ('PAYPAL', 'MERCADOPAGO') "
                + "AND (slug IS NOT NULL OR tenant_id IS NOT NULL) ORDER BY paid_at LIMIT 50",
                (rs, i) -> rs.getObject(1, UUID.class))) {
            fulfill(id);
        }
        for (String reference : jdbc.query("SELECT reference FROM orders WHERE status = 'PENDING' AND method = 'MERCADOPAGO' "
                + "AND created_at > now() - interval '2 days' ORDER BY created_at LIMIT 50", (rs, i) -> rs.getString(1))) {
            try {
                for (MercadoPagoClient.Payment payment : mercadopago.paymentsOf(reference)) {
                    if (payment.approved()) {
                        processMercadoPago(find(reference), payment);
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Reconciliacion de {} fallida: {}", reference, e.getMessage());
            }
        }
        jdbc.update("UPDATE orders SET status = 'EXPIRED' WHERE status = 'PENDING' AND method IN ('PAYPAL', 'MERCADOPAGO') AND expires_at < now() - interval '1 hour'");
    }

    private void processPayPal(Order order, PayPalClient.Capture capture) {
        if (!capture.completed()) {
            return;
        }
        boolean matches = order.reference().equals(capture.customId()) && "USD".equals(capture.currency())
                && new BigDecimal(capture.value()).movePointRight(2).intValue() == order.amountMinor();
        if (!matches) {
            log.error("El pago de PayPal {} no coincide con el pedido {}", capture.captureId(), order.reference());
            jdbc.update("UPDATE orders SET status = 'FAILED', failure_reason = 'El monto o la referencia del pago no coinciden' WHERE id = ?", order.id());
            return;
        }
        if (newEvent("PAYPAL", capture.captureId(), order.id(), "capture")) {
            markPaid(order, capture.captureId(), capture.payerEmail());
        }
        fulfill(order.id());
    }

    private void processMercadoPago(Order order, MercadoPagoClient.Payment payment) {
        if (!order.reference().equals(payment.externalReference())) {
            return;
        }
        if (payment.approved()) {
            boolean matches = "ARS".equals(payment.currency()) && payment.amount().movePointRight(2).intValue() == order.amountMinor();
            if (!matches) {
                log.error("El pago de Mercado Pago {} no coincide con el pedido {}", payment.id(), order.reference());
                jdbc.update("UPDATE orders SET status = 'FAILED', failure_reason = 'El monto o la moneda del pago no coinciden' WHERE id = ?", order.id());
                return;
            }
            if (newEvent("MERCADOPAGO", "payment:" + payment.id() + ":approved", order.id(), "approved")) {
                markPaid(order, payment.id(), payment.payerEmail());
            }
            fulfill(order.id());
        } else if ("rejected".equals(payment.status()) || "cancelled".equals(payment.status())) {
            jdbc.update("UPDATE orders SET failure_reason = ? WHERE id = ? AND status = 'PENDING'", abbreviate("Pago " + payment.status() + ": " + payment.statusDetail()), order.id());
        }
    }

    private void markPaid(Order order, String paymentId, String payerEmail) {
        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = now(), provider_payment_id = ?, payer_email = ?, failure_reason = NULL "
                + "WHERE id = ? AND status IN ('PENDING', 'EXPIRED', 'FAILED')", paymentId, payerEmail == null || payerEmail.isBlank() ? null : payerEmail.toLowerCase(), order.id());
    }

    void fulfill(UUID id) {
        try {
            tx.executeWithoutResult(status -> doFulfill(id));
        } catch (RuntimeException e) {
            log.error("No se pudo crear la cuenta del pedido {}: {}", id, e.getMessage());
            tx.executeWithoutResult(status -> jdbc.update("UPDATE orders SET failure_reason = ? WHERE id = ?", abbreviate("Pagado, pero no se pudo crear la cuenta: " + e.getMessage()), id));
        }
    }

    private void doFulfill(UUID id) {
        jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", id);
        Order order = jdbc.query(SELECT + " WHERE o.id = ?", this::row, id).stream().findFirst().orElseThrow();
        if (!"PAID".equals(order.status()) || order.fulfilledAt() != null) {
            return;
        }
        String actor = adminFor(order.region());
        String provider = BillingService.PAYPAL.equals(order.method()) ? "PayPal" : "Mercado Pago";
        String amount = "USD".equals(order.currency()) ? "US$ " + BillingService.moneyLabel(order.amountMinor()) : "$ " + (order.amountMinor() / 100) + " ARS";
        if (order.slug() == null) {
            renewFrom(order, provider, actor, amount);
            return;
        }
        if (order.tenantId() != null) {
            return;
        }
        PlanService.Batch batch = plans.generateBatch(provider + " · " + order.planName(), order.months(), 1, "Pago online " + order.reference(), actor, order.planCode());
        User user = registration.provision(order.email(), order.passwordHash(), order.businessName(), order.slug(), batch.codes().get(0), order.emailVerified());
        UUID tenantId = jdbc.queryForObject("SELECT tenant_id FROM tenant_users WHERE user_id = ?", UUID.class, user.getId());
        UUID codeId = jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, batch.codes().get(0));
        jdbc.update("UPDATE orders SET tenant_id = ?, activation_code_id = ?, confirmed_by = ?, confirmed_at = now(), fulfilled_at = now(), "
                + "password_hash = NULL WHERE id = ?", tenantId, codeId, "sistema", id);
        outbox.enqueue(EmailTemplates.PAYMENT_RECEIPT, order.email(), order.slug() + "|" + order.planName() + "|" + amount + "|" + order.reference());
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, detail) VALUES (?, 'ORDER_PAID', ?, ?, 'sistema', ?)",
                UUID.randomUUID(), id, order.reference() + " · " + order.planName(), "pago online " + order.method() + " · negocio /" + order.slug());
    }

    private void renewFrom(Order order, String provider, String actor, String amount) {
        if (order.tenantId() == null) {
            return;
        }
        PlanService.Batch batch = plans.generateBatch(provider + " · " + order.planName(), order.months(), 1, "Renovación online " + order.reference(),
                actor, order.planCode());
        String code = batch.codes().get(0);
        PlanService.Plan plan = plans.renew(order.tenantId(), code, order.email());
        UUID codeId = jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, code);
        jdbc.update("UPDATE orders SET activation_code_id = ?, confirmed_by = 'sistema', confirmed_at = now(), fulfilled_at = now() WHERE id = ?",
                codeId, order.id());
        String slug = jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, order.tenantId());
        outbox.enqueue(EmailTemplates.RENEWAL_RECEIPT, order.email(), slug + "|" + order.planName() + "|" + amount + "|" + plan.expiresAt());
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, detail) VALUES (?, 'ORDER_PAID', ?, ?, 'sistema', ?)",
                UUID.randomUUID(), order.id(), order.reference() + " · " + order.planName(), "renovación online " + order.method() + " · negocio /" + slug);
    }

    private String adminFor(String region) {
        List<String> found = jdbc.query("SELECT email FROM superadmin_accounts WHERE enabled AND role = 'ADMIN' "
                + "AND ',' || regions || ',' LIKE '%,' || ? || ',%' ORDER BY created_at LIMIT 1", (rs, i) -> rs.getString(1), region);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return superAdmin.enabled() ? superAdmin.email() : "sistema";
    }

    private boolean newEvent(String provider, String eventId, UUID orderId, String kind) {
        return jdbc.update("INSERT INTO payment_events (id, provider, event_id, order_id, kind) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (provider, event_id) DO NOTHING", UUID.randomUUID(), provider, eventId, orderId, kind) == 1;
    }

    private static final String SELECT = "SELECT o.id, o.reference, o.plan_code, p.name AS plan_name, o.months, o.region, o.method, o.buyer_email, "
            + "o.usd_cents, o.currency, coalesce(o.amount_minor, o.usd_cents) AS amount_minor, o.status, o.provider_ref, o.business_name, o.slug, "
            + "o.password_hash, o.claim_hash, o.tenant_id, o.paid_at, o.claim_used_at, o.email_verified, o.fulfilled_at FROM orders o JOIN billing_plans p ON p.code = o.plan_code";

    private Order find(String reference) {
        return jdbc.query(SELECT + " WHERE o.reference = ?", this::row, reference == null ? "" : reference.strip().toUpperCase()).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("Pedido no encontrado"));
    }

    private Order row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp paid = rs.getTimestamp("paid_at");
        Timestamp claimUsed = rs.getTimestamp("claim_used_at");
        Timestamp fulfilled = rs.getTimestamp("fulfilled_at");
        return new Order(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("plan_code"), rs.getString("plan_name"),
                rs.getInt("months"), rs.getString("region"), rs.getString("method"), rs.getString("buyer_email"), rs.getInt("usd_cents"),
                rs.getString("currency"), rs.getInt("amount_minor"), rs.getString("status"), rs.getString("provider_ref"),
                rs.getString("business_name"), rs.getString("slug"), rs.getString("password_hash"), rs.getString("claim_hash"),
                rs.getObject("tenant_id", UUID.class), paid == null ? null : paid.toInstant(), claimUsed == null ? null : claimUsed.toInstant(),
                rs.getBoolean("email_verified"), fulfilled == null ? null : fulfilled.toInstant());
    }

    private static void requireMethod(Order order, String method) {
        if (!method.equals(order.method())) {
            throw ApiException.notFound("Pedido no encontrado");
        }
    }

    private static void validate(String email, String slug, String businessName, String password) {
        if (!email.matches(EmailProofService.EMAIL_PATTERN) || email.length() > 254) {
            throw ApiException.invalidField("email", "Escribe un correo válido");
        }
        if (businessName.isEmpty() || businessName.length() > 120) {
            throw ApiException.invalidField("businessName", "Escribe el nombre de tu negocio");
        }
        if (slug.length() < 3 || slug.length() > 63 || !slug.matches(SlugRules.PATTERN)) {
            throw ApiException.invalidField("slug", "La dirección usa de 3 a 63 letras, números o guiones");
        }
        if (SlugRules.RESERVED.contains(slug)) {
            throw ApiException.badRequest("slug_reserved", "Ese identificador no está disponible");
        }
        if (password == null || password.length() < 10 || password.length() > 72 || !password.matches(".*\\p{L}.*") || !password.matches(".*\\d.*")) {
            throw ApiException.invalidField("password", "La contraseña debe tener entre 10 y 72 caracteres, con al menos una letra y un número");
        }
    }

    private String token() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 290 ? text.substring(0, 290) : text;
    }
}
