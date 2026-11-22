package Cafeteria.plan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
public class PlanService {

    public enum Status { ACTIVE, EXPIRED }

    public record Batch(UUID id, String label, int durationMonths, List<String> codes) {}

    public static final int QR_CUSTOMIZATION_MIN_MONTHS = 3;

    public record Plan(Instant expiresAt, Status status, Integer months, boolean qrCustomization) {}

    public record Redemption(Instant expiresAt, int months) {}

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;

    public PlanService(JdbcTemplate jdbc, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public static Plan planOf(Tenant tenant) {
        Instant expires = tenant.getPlanExpiresAt();
        boolean expired = expires != null && !expires.isAfter(Instant.now());
        Integer months = tenant.getPlanMonths();
        return new Plan(expires, expired ? Status.EXPIRED : Status.ACTIVE, months,
                months == null || months >= QR_CUSTOMIZATION_MIN_MONTHS);
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
        if (!ActivationCodeFormat.DURATIONS.contains(durationMonths)) {
            throw ApiException.invalidField("durationMonths", "La duración debe ser de 1, 3, 6 o 12 meses");
        }
        if (quantity < 1 || quantity > 500) {
            throw ApiException.invalidField("quantity", "La cantidad debe estar entre 1 y 500");
        }
        UUID batchId = UUID.randomUUID();
        String cleanLabel = label == null || label.isBlank() ? durationMonths + " meses" : label.strip();
        jdbc.update("INSERT INTO code_batches (id, label, duration_months, quantity, note, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                batchId, cleanLabel, durationMonths, quantity, note == null || note.isBlank() ? null : note.strip(), actor);
        List<String> codes = new ArrayList<>(quantity);
        while (codes.size() < quantity) {
            String code = ActivationCodeFormat.generate();
            int inserted = jdbc.update("INSERT INTO activation_codes (id, code, batch_id, duration_months) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (code) DO NOTHING", UUID.randomUUID(), code, batchId, durationMonths);
            if (inserted == 1) {
                codes.add(code);
            }
        }
        return new Batch(batchId, cleanLabel, durationMonths, codes);
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
        List<Integer> months = code.isEmpty() ? List.of() : jdbc.query(
                "UPDATE activation_codes SET status = 'REDEEMED', redeemed_at = now(), redeemed_by_email = ?, tenant_id = ? "
                        + "WHERE code = ? AND status = 'AVAILABLE' RETURNING duration_months",
                (rs, i) -> rs.getInt(1), email, tenantId, code);
        if (months.isEmpty()) {
            throw invalidCode();
        }
        return new Redemption(plusMonths(Instant.now(), months.get(0)), months.get(0));
    }

    @Transactional
    public Plan renew(UUID tenantId, String rawCode, String email) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        String code = ActivationCodeFormat.normalize(rawCode);
        List<Integer> months = code.isEmpty() ? List.of() : jdbc.query(
                "SELECT duration_months FROM activation_codes WHERE code = ? AND status = 'AVAILABLE' FOR UPDATE",
                (rs, i) -> rs.getInt(1), code);
        if (months.isEmpty()) {
            throw invalidCode();
        }
        jdbc.update("DELETE FROM activation_codes WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE activation_codes SET status = 'REDEEMED', redeemed_at = now(), redeemed_by_email = ?, tenant_id = ? "
                + "WHERE code = ?", email, tenantId, code);
        Instant now = Instant.now();
        Instant current = tenant.getPlanExpiresAt();
        Instant base = current != null && current.isAfter(now) ? current : now;
        tenant.setPlanExpiresAt(plusMonths(base, months.get(0)));
        tenant.setPlanMonths(months.get(0));
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
