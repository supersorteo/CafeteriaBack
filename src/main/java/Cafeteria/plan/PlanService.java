package Cafeteria.plan;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.shared.ApiException;
import Cafeteria.seed.DemoSiteSeeder;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
public class PlanService {

    public enum Status {
        ACTIVE, GRACE, EXPIRED, ARCHIVED;

        public boolean locked() {
            return this == EXPIRED || this == ARCHIVED;
        }
    }

    public static final Duration WARNING = Duration.ofDays(7);
    public static final Duration GRACE = Duration.ofDays(3);
    public static final Duration ARCHIVE_AFTER = Duration.ofDays(90);

    public record Batch(UUID id, String label, int durationMonths, List<String> codes, String planCode) {}

    public record Plan(Instant expiresAt, Status status, Integer months, boolean qrCustomization, String planCode, Instant graceEndsAt) {}

    public record Redemption(Instant expiresAt, int months, String planCode) {}

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final Entitlements entitlements;

    public PlanService(JdbcTemplate jdbc, TenantRepository tenants, Entitlements entitlements) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.entitlements = entitlements;
    }

    public Optional<String> buyerEmailOfCode(String code) {
        return jdbc.query("SELECT lower(o.buyer_email) FROM orders o JOIN activation_codes c ON c.id = o.activation_code_id "
                + "WHERE c.code = ?", (rs, i) -> rs.getString(1), code == null ? "" : code.strip().toUpperCase()).stream().findFirst();
    }

    public static Plan planOf(Tenant tenant) {
        boolean demo = DemoSiteSeeder.SLUG.equals(tenant.getSlug());
        Instant expires = demo ? null : tenant.getPlanExpiresAt();
        Integer months = tenant.getPlanMonths();
        String planCode = tenant.getPlanCode() == null ? Entitlements.DEFAULT_PLAN : tenant.getPlanCode();
        return new Plan(expires, statusAt(expires, Instant.now()), months, true, planCode, expires == null ? null : expires.plus(GRACE));
    }

    public static String stageOf(Instant expires) {
        Instant now = Instant.now();
        Status status = statusAt(expires, now);
        if (status == Status.ACTIVE) {
            return expires != null && expires.minus(WARNING).isBefore(now) ? "SOON" : "ACTIVE";
        }
        return status.name();
    }

    public static Status statusAt(Instant expires, Instant now) {
        if (expires == null || expires.isAfter(now)) {
            return Status.ACTIVE;
        }
        if (expires.plus(GRACE).isAfter(now)) {
            return Status.GRACE;
        }
        return expires.plus(ARCHIVE_AFTER).isAfter(now) ? Status.EXPIRED : Status.ARCHIVED;
    }

    public static Instant plusMonths(Instant from, int months) {
        return from.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    @Transactional(readOnly = true)
    public boolean hasBatches() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM code_batches", Integer.class);
        return count != null && count > 0;
    }

    @Transactional
    public Batch generateBatch(String label, int durationMonths, int quantity, String note, String actor) {
        return generateBatch(label, durationMonths, quantity, note, actor, null);
    }

    @Transactional
    public Batch generateBatch(String label, int durationMonths, int quantity, String note, String actor, String planCode) {
        String plan = planCode == null || planCode.isBlank() ? Entitlements.DEFAULT_PLAN : planCode.strip().toUpperCase();
        if (!entitlements.exists(plan)) {
            throw ApiException.invalidField("planCode", "El plan no existe");
        }
        if (!ActivationCodeFormat.DURATIONS.contains(durationMonths)) {
            throw ApiException.invalidField("durationMonths", "La duración debe ser de 1, 3, 6 o 12 meses");
        }
        if (quantity < 1 || quantity > 500) {
            throw ApiException.invalidField("quantity", "La cantidad debe estar entre 1 y 500");
        }
        UUID batchId = UUID.randomUUID();
        String cleanLabel = label == null || label.isBlank() ? durationMonths + " meses" : label.strip();
        jdbc.update("INSERT INTO code_batches (id, label, duration_months, quantity, note, created_by, plan_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchId, cleanLabel, durationMonths, quantity, note == null || note.isBlank() ? null : note.strip(), actor, plan);
        List<String> codes = new ArrayList<>(quantity);
        while (codes.size() < quantity) {
            String code = ActivationCodeFormat.generate();
            int inserted = jdbc.update("INSERT INTO activation_codes (id, code, batch_id, duration_months, plan_code) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING", UUID.randomUUID(), code, batchId, durationMonths, plan);
            if (inserted == 1) {
                codes.add(code);
            }
        }
        return new Batch(batchId, cleanLabel, durationMonths, codes, plan);
    }

    @Transactional(readOnly = true)
    public boolean isAvailable(String rawCode) {
        String code = ActivationCodeFormat.normalize(rawCode);
        if (code.isEmpty()) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM activation_codes WHERE code = ? AND status = 'AVAILABLE'",
                Integer.class, code);
        return count != null && count > 0;
    }

    @Transactional
    public Redemption redeemForRegistration(String rawCode, UUID tenantId, String email) {
        String code = ActivationCodeFormat.normalize(rawCode);
        List<Redemption> found = code.isEmpty() ? List.of() : jdbc.query(
                "UPDATE activation_codes SET status = 'REDEEMED', redeemed_at = now(), redeemed_by_email = ?, tenant_id = ? "
                        + "WHERE code = ? AND status = 'AVAILABLE' RETURNING duration_months, plan_code",
                (rs, i) -> new Redemption(plusMonths(Instant.now(), rs.getInt(1)), rs.getInt(1),
                        rs.getString(2) == null ? Entitlements.DEFAULT_PLAN : rs.getString(2)), email, tenantId, code);
        if (found.isEmpty()) {
            throw invalidCode();
        }
        return found.get(0);
    }

    @Transactional
    public Plan renew(UUID tenantId, String rawCode, String email) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        String code = ActivationCodeFormat.normalize(rawCode);
        List<Object[]> found = code.isEmpty() ? List.of() : jdbc.query(
                "SELECT duration_months, plan_code FROM activation_codes WHERE code = ? AND status = 'AVAILABLE' FOR UPDATE",
                (rs, i) -> new Object[] { rs.getInt(1), rs.getString(2) == null ? Entitlements.DEFAULT_PLAN : rs.getString(2) }, code);
        if (found.isEmpty()) {
            throw invalidCode();
        }
        List<Integer> months = List.of((Integer) found.get(0)[0]);
        String newPlan = (String) found.get(0)[1];
        jdbc.update("DELETE FROM activation_codes WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', redeemed_at = now(), redeemed_by_email = ?, tenant_id = ? "
                + "WHERE code = ?", email, tenantId, code);
        Instant now = Instant.now();
        Instant current = tenant.getPlanExpiresAt();
        Instant base = current != null && current.plus(GRACE).isAfter(now) ? current : now;
        tenant.setPlanExpiresAt(plusMonths(base, months.get(0)));
        tenant.setPlanMonths(months.get(0));
        tenant.setPlanCode(newPlan);
        tenants.saveAndFlush(tenant);
        audit("PLAN_RENEWED", tenant, email, code + " (" + months.get(0) + " meses)");
        return planOf(tenant);
    }

    public void audit(String action, Tenant tenant, String actor, String detail) {
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, detail) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, tenant.getId(), tenant.getName() + " (" + tenant.getSlug() + ")", actor, detail);
    }

    static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_code", "El código no es válido o ya fue usado");
    }
}
