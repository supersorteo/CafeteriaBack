package Cafeteria.superadmin;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.media.MediaService;
import Cafeteria.shared.ApiException;
import Cafeteria.superadmin.SuperAdminDtos.AuditEntry;
import Cafeteria.superadmin.SuperAdminDtos.Stats;
import Cafeteria.superadmin.SuperAdminDtos.TenantPage;
import Cafeteria.superadmin.SuperAdminDtos.TenantRow;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
public class SuperAdminService {

    private static final String LIST_FROM = " FROM tenants t "
            + "LEFT JOIN tenant_users tu ON tu.tenant_id = t.id AND tu.role = 'OWNER' "
            + "LEFT JOIN users u ON u.id = tu.user_id ";

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final MediaService media;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SuperAdminService(JdbcTemplate jdbc, TenantRepository tenants, MediaService media,
            FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.media = media;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return jdbc.queryForObject("SELECT count(*), count(*) FILTER (WHERE suspended_at IS NULL), "
                + "count(*) FILTER (WHERE suspended_at IS NOT NULL), "
                + "count(*) FILTER (WHERE published AND suspended_at IS NULL), "
                + "count(*) FILTER (WHERE created_at > now() - interval '7 days') FROM tenants",
                (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
    }

    @Transactional(readOnly = true)
    public TenantPage list(String query, String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String like = "%" + query.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(t.name) LIKE ? OR lower(t.slug) LIKE ? OR lower(u.email) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if ("active".equals(status)) {
            where.append(" AND t.suspended_at IS NULL");
        } else if ("suspended".equals(status)) {
            where.append(" AND t.suspended_at IS NOT NULL");
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + LIST_FROM + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);
        List<TenantRow> rows = jdbc.query("SELECT t.id, t.slug, t.name, t.published, t.suspended_at, u.email, "
                + "u.email_verified_at IS NOT NULL AS verified, "
                + "(SELECT count(*) FROM tenant_users e WHERE e.tenant_id = t.id AND e.role <> 'OWNER') AS editors, "
                + "t.created_at" + LIST_FROM + where + " ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Timestamp suspended = rs.getTimestamp("suspended_at");
                    return new TenantRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                            rs.getBoolean("published"), suspended != null,
                            suspended == null ? null : suspended.toInstant(), rs.getString("email"),
                            rs.getBoolean("verified"), rs.getLong("editors"), rs.getTimestamp("created_at").toInstant());
                }, pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        return new TenantPage(rows, safePage, safeSize, totalElements, (int) Math.ceil(totalElements / (double) safeSize));
    }

    @Transactional
    public void suspend(UUID tenantId, String actor, String ip) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        if (tenant.getSuspendedAt() != null) {
            return;
        }
        tenant.setSuspendedAt(java.time.Instant.now());
        tenants.saveAndFlush(tenant);
        memberEmails(tenantId).forEach(this::closeSessions);
        audit("TENANT_SUSPENDED", tenant, actor, ip, null);
    }

    @Transactional
    public void activate(UUID tenantId, String actor, String ip) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        if (tenant.getSuspendedAt() == null) {
            return;
        }
        tenant.setSuspendedAt(null);
        tenants.saveAndFlush(tenant);
        audit("TENANT_ACTIVATED", tenant, actor, ip, null);
    }

    @Transactional
    public void delete(UUID tenantId, String confirmSlug, String actor, String ip) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        if (tenant.getSuspendedAt() == null) {
            throw ApiException.conflict("not_suspended", "Suspende el negocio antes de eliminarlo");
        }
        if (confirmSlug == null || !tenant.getSlug().equals(confirmSlug.strip())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "slug_mismatch", "La dirección escrita no coincide");
        }
        List<String> emails = memberEmails(tenantId);
        List<UUID> userIds = jdbc.query("SELECT user_id FROM tenant_users WHERE tenant_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId);

        for (String table : List.of("site_images", "site_spaces", "site_stats", "site_highlights", "menu_items", "categories")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId);
        }
        int files = media.deleteAllOfTenant(tenantId);
        jdbc.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenantId);
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM account_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        for (String email : emails) {
            jdbc.update("DELETE FROM email_outbox WHERE recipient = ?", email);
            closeSessions(email);
        }
        audit("TENANT_DELETED", tenant, actor, ip, "usuarios=" + userIds.size() + ", imágenes=" + files);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(int limit) {
        return jdbc.query("SELECT id, at, action, target_label, actor, ip, detail FROM audit_log ORDER BY at DESC LIMIT ?",
                (rs, i) -> new AuditEntry(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getString("action"), rs.getString("target_label"), rs.getString("actor"), rs.getString("ip"),
                        rs.getString("detail")),
                Math.min(Math.max(limit, 1), 200));
    }

    private List<String> memberEmails(UUID tenantId) {
        return jdbc.query("SELECT u.email FROM tenant_users tu JOIN users u ON u.id = tu.user_id WHERE tu.tenant_id = ?",
                (rs, i) -> rs.getString(1), tenantId);
    }

    private void closeSessions(String email) {
        sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
    }

    private void audit(String action, Tenant tenant, String actor, String ip, String detail) {
        jdbc.update("INSERT INTO audit_log (id, action, target_id, target_label, actor, ip, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, tenant.getId(), tenant.getName() + " (" + tenant.getSlug() + ")", actor, ip, detail);
    }
}
