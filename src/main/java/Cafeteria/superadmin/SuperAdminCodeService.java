package Cafeteria.superadmin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.plan.PlanService;
import Cafeteria.shared.ApiException;

@Service
public class SuperAdminCodeService {

    public record CodeRow(UUID id, String code, int durationMonths, String status, UUID batchId, String batchLabel,
            Instant createdAt, Instant redeemedAt, String redeemedByEmail, String tenantSlug, String tenantName,
            Instant tenantCreatedAt, Instant planExpiresAt, String planStatus, String planCode) {}

    public record CodePage(List<CodeRow> content, int page, int size, long totalElements, int totalPages) {}

    public record BatchRow(UUID id, String label, int durationMonths, int quantity, String note, String createdBy,
            Instant createdAt, long available, long redeemed, long disabled, String planCode) {}

    public record CodeStats(long available, long redeemed, long disabled, long plansExpiringSoon, long plansExpired) {}

    private static final String FROM = " FROM activation_codes c JOIN code_batches b ON b.id = c.batch_id "
            + "LEFT JOIN tenants t ON t.id = c.tenant_id ";

    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final SuperAdminService businesses;
    private final SuperAdminScopes scopes;

    public SuperAdminCodeService(JdbcTemplate jdbc, PlanService plans, SuperAdminService businesses, SuperAdminScopes scopes) {
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.plans = plans;
        this.businesses = businesses;
    }

    @Transactional
    public PlanService.Batch createBatch(String label, int durationMonths, int quantity, String note, String planCode, String actor,
            String ip) {
        PlanService.Batch batch = plans.generateBatch(label, durationMonths, quantity, note, actor, planCode);
        audit("CODE_BATCH_CREATED", batch.id(), batch.label() + " · " + quantity + " × " + durationMonths + " meses", actor, ip,
                "plan " + batch.planCode());
        return batch;
    }

    @Transactional(readOnly = true)
    public CodePage list(SuperAdminScopes.Viewer viewer, String status, Integer durationMonths, UUID batchId, String query, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        int safePage = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        where.append(scopes.authorFilter(viewer, "b.created_by", args));
        if (status != null && List.of("AVAILABLE", "REDEEMED", "DISABLED").contains(status.toUpperCase())) {
            where.append(" AND c.status = ?");
            args.add(status.toUpperCase());
        }
        if (durationMonths != null) {
            where.append(" AND c.duration_months = ?");
            args.add(durationMonths);
        }
        if (batchId != null) {
            where.append(" AND c.batch_id = ?");
            args.add(batchId);
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(c.code) LIKE ? OR lower(c.redeemed_by_email) LIKE ? OR lower(t.slug) LIKE ? OR lower(t.name) LIKE ?)");
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);
        List<CodeRow> rows = jdbc.query("SELECT c.id, c.code, c.duration_months, c.status, c.batch_id, b.label, c.created_at, "
                + "c.redeemed_at, c.redeemed_by_email, t.slug, t.name, t.created_at AS tenant_created_at, t.plan_expires_at, c.plan_code" + FROM + where
                + " ORDER BY (c.status = 'REDEEMED') DESC, c.redeemed_at DESC NULLS LAST, c.created_at DESC, c.code LIMIT ? OFFSET ?",
                (rs, i) -> new CodeRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("duration_months"),
                        rs.getString("status"), rs.getObject("batch_id", UUID.class), rs.getString("label"),
                        rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("redeemed_at")),
                        rs.getString("redeemed_by_email"), rs.getString("slug"), rs.getString("name"),
                        instant(rs.getTimestamp("tenant_created_at")), instant(rs.getTimestamp("plan_expires_at")),
                        planStatus(rs.getTimestamp("plan_expires_at"), rs.getString("slug")), rs.getString("plan_code")),
                pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        return new CodePage(rows, safePage, safeSize, totalElements, (int) Math.ceil(totalElements / (double) safeSize));
    }

    @Transactional(readOnly = true)
    public List<BatchRow> batches(SuperAdminScopes.Viewer viewer) {
        List<Object> args = new ArrayList<>();
        String scope = scopes.authorFilter(viewer, "b.created_by", args);
        return jdbc.query("SELECT b.id, b.label, b.duration_months, b.quantity, b.note, b.created_by, b.created_at, b.plan_code, "
                + "count(*) FILTER (WHERE c.status = 'AVAILABLE') AS available, "
                + "count(*) FILTER (WHERE c.status = 'REDEEMED') AS redeemed, "
                + "count(*) FILTER (WHERE c.status = 'DISABLED') AS disabled "
                + "FROM code_batches b LEFT JOIN activation_codes c ON c.batch_id = b.id WHERE 1 = 1" + scope + " "
                + "GROUP BY b.id ORDER BY b.created_at DESC LIMIT 100",
                (rs, i) -> new BatchRow(rs.getObject("id", UUID.class), rs.getString("label"), rs.getInt("duration_months"),
                        rs.getInt("quantity"), rs.getString("note"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("available"), rs.getLong("redeemed"),
                        rs.getLong("disabled"), rs.getString("plan_code")), args.toArray());
    }

    @Transactional(readOnly = true)
    public CodeStats stats(SuperAdminScopes.Viewer viewer) {
        long[] byStatus = new long[3];
        List<Object> codeArgs = new ArrayList<>();
        String byAuthor = scopes.authorFilter(viewer, "b.created_by", codeArgs);
        jdbc.query("SELECT c.status, count(*) FROM activation_codes c JOIN code_batches b ON b.id = c.batch_id WHERE 1 = 1"
                + byAuthor + " GROUP BY c.status", rs -> {
            switch (rs.getString(1)) {
                case "AVAILABLE" -> byStatus[0] = rs.getLong(2);
                case "REDEEMED" -> byStatus[1] = rs.getLong(2);
                case "DISABLED" -> byStatus[2] = rs.getLong(2);
                default -> { }
            }
        }, codeArgs.toArray());
        List<Object> soonArgs = new ArrayList<>();
        String soonScope = scopes.tenantFilter(viewer, soonArgs);
        Long soon = jdbc.queryForObject("SELECT count(*) FROM tenants t WHERE t.plan_expires_at > now() "
                + "AND t.plan_expires_at <= now() + interval '7 days'" + soonScope, Long.class, soonArgs.toArray());
        List<Object> expiredArgs = new ArrayList<>();
        String expiredScope = scopes.tenantFilter(viewer, expiredArgs);
        Long expired = jdbc.queryForObject("SELECT count(*) FROM tenants t WHERE t.plan_expires_at <= now()" + expiredScope,
                Long.class, expiredArgs.toArray());
        return new CodeStats(byStatus[0], byStatus[1], byStatus[2], soon == null ? 0 : soon, expired == null ? 0 : expired);
    }

    private record CodeState(String code, String status, UUID tenantId, String slug) {}

    private CodeState lock(UUID id) {
        List<CodeState> found = jdbc.query("SELECT c.code, c.status, c.tenant_id, t.slug FROM activation_codes c "
                + "LEFT JOIN tenants t ON t.id = c.tenant_id WHERE c.id = ? FOR UPDATE OF c",
                (rs, i) -> new CodeState(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)), id);
        if (found.isEmpty()) {
            throw ApiException.notFound("Código no encontrado");
        }
        return found.get(0);
    }

    @Transactional
    public void disable(UUID id, String actor, String ip) {
        CodeState state = lock(id);
        if (state.tenantId() != null) {
            if (!"REDEEMED".equals(state.status())) {
                throw conflict();
            }
            businesses.suspend(state.tenantId(), actor, ip);
            audit("CODE_DISABLED", id, state.code(), actor, ip, "negocio suspendido: /" + state.slug());
            return;
        }
        if (!"AVAILABLE".equals(state.status())) {
            throw conflict();
        }
        jdbc.update("UPDATE activation_codes SET status = 'DISABLED' WHERE id = ?", id);
        audit("CODE_DISABLED", id, state.code(), actor, ip, null);
    }

    @Transactional
    public void enable(UUID id, String actor, String ip) {
        CodeState state = lock(id);
        if (!"DISABLED".equals(state.status())) {
            throw conflict();
        }
        if (state.tenantId() != null) {
            businesses.activate(state.tenantId(), actor, ip);
            audit("CODE_ENABLED", id, state.code(), actor, ip, "negocio reactivado: /" + state.slug());
            return;
        }
        jdbc.update("UPDATE activation_codes SET status = 'AVAILABLE' WHERE id = ?", id);
        audit("CODE_ENABLED", id, state.code(), actor, ip, null);
    }

    @Transactional
    public void delete(UUID id, String confirmSlug, String actor, String ip) {
        CodeState state = lock(id);
        if (state.tenantId() != null) {
            businesses.deleteWithBusiness(state.tenantId(), confirmSlug, actor, ip, "Código " + state.code());
            audit("CODE_DELETED", id, state.code(), actor, ip, "se eliminó el negocio /" + state.slug() + " y todos sus datos");
            return;
        }
        jdbc.update("DELETE FROM activation_codes WHERE id = ?", id);
        audit("CODE_DELETED", id, state.code(), actor, ip, null);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "code_state_conflict", "El código ya no está en el estado esperado");
    }

    private void audit(String action, UUID targetId, String label, String actor, String ip, String detail) {
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, ip, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, targetId, label, actor, ip, detail);
    }

    private static String planStatus(Timestamp expires, String slug) {
        if (slug == null) {
            return null;
        }
        return Cafeteria.plan.PlanService.stageOf(expires == null ? null : expires.toInstant());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
