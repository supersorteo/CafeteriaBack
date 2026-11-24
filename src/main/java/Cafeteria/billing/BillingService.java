package Cafeteria.billing;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.mail.EmailOutbox;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.plan.PlanService;
import Cafeteria.shared.ApiException;
import Cafeteria.superadmin.SuperAdminScopes;

@Service
public class BillingService {

    public static final String WHATSAPP = "WHATSAPP";
    static final int MAX_PENDING_PER_EMAIL = 3;
    static final int WHATSAPP_ORDER_DAYS = 7;
    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public record PlanOption(String code, String name, int months, int usdCents, int maxMenuItems, int maxActiveCoupons,
            int maxGallery, int maxTableQr, boolean fullBranding, boolean advancedCoupons, boolean tableQr,
            boolean prioritySupport, boolean stats) {}

    public record RegionOption(String code, String name, boolean whatsapp, String whatsappNumber) {}

    public record Options(List<PlanOption> plans, List<RegionOption> regions) {}

    public record NewOrder(String planCode, String region, String email, String name) {}

    public record OrderReceipt(String reference, String status, String planName, int months, int usdCents, String region,
            String whatsappNumber, String message, Instant expiresAt) {}

    public record OrderRow(UUID id, String reference, String planCode, String planName, int months, String region,
            String buyerName, String buyerEmail, int usdCents, String status, String confirmedBy, Instant confirmedAt,
            Instant createdAt, Instant expiresAt, String activationCode) {}

    public record OrderPage(List<OrderRow> content, int page, int size, long totalElements, int totalPages) {}

    private static final String ORDER_COLUMNS = "o.id, o.reference, o.plan_code, p.name AS plan_name, o.months, o.region, "
            + "o.buyer_name, o.buyer_email, o.usd_cents, o.status, o.confirmed_by, o.confirmed_at, o.created_at, "
            + "o.expires_at, c.code AS activation_code";
    private static final String ORDER_FROM = " FROM orders o JOIN billing_plans p ON p.code = o.plan_code "
            + "LEFT JOIN activation_codes c ON c.id = o.activation_code_id ";

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final EmailOutbox outbox;
    private final BillingProperties config;
    private final SuperAdminScopes scopes;
    private final SecureRandom random = new SecureRandom();

    public BillingService(JdbcTemplate jdbc, PlanService plans, EmailOutbox outbox, BillingProperties config,
            SuperAdminScopes scopes) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.outbox = outbox;
        this.config = config;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public Options options() {
        List<PlanOption> planOptions = jdbc.query("SELECT p.code, p.name, pr.months, pr.usd_cents, p.max_menu_items, "
                + "p.max_active_coupons, p.max_gallery, p.max_table_qr, p.full_branding, p.advanced_coupons, p.table_qr, "
                + "p.priority_support, p.stats FROM billing_prices pr JOIN billing_plans p ON p.code = pr.plan_code "
                + "WHERE pr.active ORDER BY p.sort_order, pr.months",
                (rs, i) -> new PlanOption(rs.getString("code"), rs.getString("name"), rs.getInt("months"),
                        rs.getInt("usd_cents"), rs.getInt("max_menu_items"), rs.getInt("max_active_coupons"),
                        rs.getInt("max_gallery"), rs.getInt("max_table_qr"), rs.getBoolean("full_branding"),
                        rs.getBoolean("advanced_coupons"), rs.getBoolean("table_qr"), rs.getBoolean("priority_support"),
                        rs.getBoolean("stats")));
        List<RegionOption> regionOptions = jdbc.query("SELECT code, name FROM billing_regions WHERE active ORDER BY sort_order",
                (rs, i) -> {
                    String number = config.whatsappOf(rs.getString("code"));
                    return new RegionOption(rs.getString("code"), rs.getString("name"), !number.isEmpty(), number);
                });
        return new Options(planOptions, regionOptions);
    }

    @Transactional
    public OrderReceipt createOrder(NewOrder request) {
        String email = request.email() == null ? "" : request.email().strip().toLowerCase();
        String plan = request.planCode() == null ? "" : request.planCode().strip().toUpperCase();
        String region = request.region() == null ? "" : request.region().strip().toUpperCase();
        Options options = options();
        PlanOption price = options.plans().stream().filter(p -> p.code().equals(plan)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "Elige un plan disponible"));
        RegionOption zone = options.regions().stream().filter(r -> r.code().equals(region)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("region", "Elige tu país"));
        if (!zone.whatsapp()) {
            throw ApiException.conflict("method_unavailable", "Este método no está disponible en tu país por ahora");
        }
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM orders WHERE lower(buyer_email) = ? AND status = 'PENDING'",
                Integer.class, email);
        if (pending != null && pending >= MAX_PENDING_PER_EMAIL) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_orders",
                    "Ya tienes pedidos pendientes. Espera a que los confirmemos.");
        }
        String reference = newReference();
        Instant expires = Instant.now().plus(WHATSAPP_ORDER_DAYS, ChronoUnit.DAYS);
        String name = request.name() == null || request.name().isBlank() ? null : request.name().strip();
        jdbc.update("INSERT INTO orders (id, reference, plan_code, months, region, method, buyer_name, buyer_email, usd_cents, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), reference, plan, price.months(), region, WHATSAPP,
                name, email, price.usdCents(), Timestamp.from(expires));
        return receipt(reference);
    }

    @Transactional(readOnly = true)
    public OrderReceipt receipt(String reference) {
        List<OrderReceipt> found = jdbc.query("SELECT o.reference, o.status, p.name, o.months, o.usd_cents, o.region, o.expires_at "
                + "FROM orders o JOIN billing_plans p ON p.code = o.plan_code WHERE o.reference = ?",
                (rs, i) -> {
                    String number = config.whatsappOf(rs.getString("region"));
                    int cents = rs.getInt("usd_cents");
                    String message = "Hola, quiero contratar el plan " + rs.getString("name") + " (US$ " + money(cents)
                            + " por " + rs.getInt("months") + " mes). Mi pedido es " + rs.getString("reference") + ".";
                    return new OrderReceipt(rs.getString("reference"), rs.getString("status"), rs.getString("name"),
                            rs.getInt("months"), cents, rs.getString("region"), number, message,
                            rs.getTimestamp("expires_at").toInstant());
                }, reference == null ? "" : reference.strip().toUpperCase());
        if (found.isEmpty()) {
            throw ApiException.notFound("Pedido no encontrado");
        }
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public OrderPage list(SuperAdminScopes.Viewer viewer, String status, String query, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        where.append(scopes.regionFilter(viewer, "o.region", args));
        if (status != null && List.of("PENDING", "PAID", "CANCELLED", "EXPIRED").contains(status.toUpperCase())) {
            where.append(" AND o.status = ?");
            args.add(status.toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(o.reference) LIKE ? OR lower(o.buyer_email) LIKE ? OR lower(coalesce(o.buyer_name, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + ORDER_FROM + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);
        List<OrderRow> rows = jdbc.query("SELECT " + ORDER_COLUMNS + ORDER_FROM + where
                + " ORDER BY (o.status = 'PENDING') DESC, o.created_at DESC LIMIT ? OFFSET ?", this::row, pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        return new OrderPage(rows, safePage, safeSize, totalElements, (int) Math.ceil(totalElements / (double) safeSize));
    }

    @Transactional
    public OrderRow confirm(SuperAdminScopes.Viewer viewer, UUID id, String actor, String ip) {
        OrderRow order = lock(viewer, id);
        if ("PAID".equals(order.status())) {
            throw ApiException.conflict("order_state_conflict", "Este pedido ya está confirmado");
        }
        if ("CANCELLED".equals(order.status())) {
            throw ApiException.conflict("order_state_conflict", "Este pedido fue cancelado");
        }
        PlanService.Batch batch = plans.generateBatch("Pedido " + order.reference(), order.months(), 1,
                "Pedido " + order.reference(), actor, order.planCode());
        String code = batch.codes().get(0);
        UUID codeId = jdbc.queryForObject("SELECT id FROM activation_codes WHERE code = ?", UUID.class, code);
        jdbc.update("UPDATE orders SET status = 'PAID', activation_code_id = ?, confirmed_by = ?, confirmed_at = now() WHERE id = ?",
                codeId, actor, id);
        outbox.enqueue(EmailTemplates.ACTIVATION_CODE, order.buyerEmail(), code);
        audit("ORDER_CONFIRMED", id, order.reference() + " · " + order.planName(), actor, ip, "código enviado a " + order.buyerEmail());
        return lock(viewer, id);
    }

    @Transactional
    public void cancel(SuperAdminScopes.Viewer viewer, UUID id, String actor, String ip) {
        OrderRow order = lock(viewer, id);
        if (!"PENDING".equals(order.status()) && !"EXPIRED".equals(order.status())) {
            throw ApiException.conflict("order_state_conflict", "Solo se puede cancelar un pedido pendiente");
        }
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", id);
        audit("ORDER_CANCELLED", id, order.reference() + " · " + order.planName(), actor, ip, null);
    }

    @Transactional
    public int expireOld() {
        return jdbc.update("UPDATE orders SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at < now()");
    }

    @Transactional
    public void setPrice(String planCode, int months, int usdCents, String actor, String ip) {
        if (usdCents < 100 || usdCents > 100_000) {
            throw ApiException.invalidField("usdCents", "El precio debe estar entre US$ 1 y US$ 1000");
        }
        int updated = jdbc.update("UPDATE billing_prices SET usd_cents = ? WHERE plan_code = ? AND months = ?", usdCents,
                planCode == null ? "" : planCode.strip().toUpperCase(), months);
        if (updated == 0) {
            throw ApiException.notFound("Precio no encontrado");
        }
        audit("PRICE_CHANGED", null, planCode + " · " + months + " mes", actor, ip, "US$ " + money(usdCents));
    }

    private OrderRow lock(SuperAdminScopes.Viewer viewer, UUID id) {
        jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", id);
        List<OrderRow> found = jdbc.query("SELECT " + ORDER_COLUMNS + ORDER_FROM + " WHERE o.id = ?", this::row, id);
        if (found.isEmpty() || !scopes.canSeeRegion(viewer, found.get(0).region())) {
            throw ApiException.notFound("Pedido no encontrado");
        }
        return found.get(0);
    }

    private OrderRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new OrderRow(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("plan_code"),
                rs.getString("plan_name"), rs.getInt("months"), rs.getString("region"), rs.getString("buyer_name"),
                rs.getString("buyer_email"), rs.getInt("usd_cents"), rs.getString("status"), rs.getString("confirmed_by"),
                confirmed == null ? null : confirmed.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("activation_code"));
    }

    private String newReference() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder reference = new StringBuilder("PED-");
            for (int i = 0; i < 8; i++) {
                reference.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
            }
            Integer used = jdbc.queryForObject("SELECT count(*) FROM orders WHERE reference = ?", Integer.class, reference.toString());
            if (used != null && used == 0) {
                return reference.toString();
            }
        }
        throw new IllegalStateException("No se pudo generar una referencia");
    }

    private static String money(int cents) {
        return cents % 100 == 0 ? String.valueOf(cents / 100) : String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
    }

    private void audit(String action, UUID targetId, String label, String actor, String ip, String detail) {
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, ip, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, targetId, label, actor, ip, detail);
    }
}
