package Cafeteria.plan;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import Cafeteria.mail.EmailOutbox;
import Cafeteria.mail.EmailTemplates;
import Cafeteria.seed.DemoSiteSeeder;

@Service
public class PlanNoticeService {

    public enum Kind {
        SOON_7(-7, -3), SOON_3(-3, -1), SOON_1(-1, 0), GRACE(0, 3), PAUSED(3, 10), PAUSED_7(10, 33), PAUSED_30(33, 90), ARCHIVED(90, 97);

        final int fromDay;
        final int toDay;

        Kind(int fromDay, int toDay) {
            this.fromDay = fromDay;
            this.toDay = toDay;
        }
    }

    private record Due(UUID tenantId, String name, String slug, Instant expiresAt, String ownerEmail) {}

    private final JdbcTemplate jdbc;
    private final EmailOutbox outbox;

    public PlanNoticeService(JdbcTemplate jdbc, EmailOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public int sendDue() {
        int sent = 0;
        for (Kind kind : Kind.values()) {
            List<Due> due = jdbc.query("SELECT t.id, t.name, t.slug, t.plan_expires_at, u.email FROM tenants t "
                    + "JOIN tenant_users tu ON tu.tenant_id = t.id AND tu.role = 'OWNER' JOIN users u ON u.id = tu.user_id "
                    + "WHERE t.plan_expires_at IS NOT NULL AND t.suspended_at IS NULL AND t.slug <> ? "
                    + "AND t.plan_expires_at + (? * interval '1 day') <= now() AND t.plan_expires_at + (? * interval '1 day') > now()",
                    (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                    DemoSiteSeeder.SLUG, kind.fromDay, kind.toDay);
            for (Due tenant : due) {
                int claimed = jdbc.update("INSERT INTO plan_notices (tenant_id, kind, period_expires_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        tenant.tenantId(), kind.name(), Timestamp.from(tenant.expiresAt()));
                if (claimed == 1) {
                    outbox.enqueue(EmailTemplates.PLAN_NOTICE, tenant.ownerEmail(),
                            kind.name() + "|" + tenant.slug() + "|" + tenant.expiresAt() + "|" + tenant.name());
                    sent++;
                }
            }
        }
        return sent;
    }
}
