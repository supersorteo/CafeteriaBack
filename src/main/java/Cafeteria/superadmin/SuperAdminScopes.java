package Cafeteria.superadmin;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import Cafeteria.shared.ApiException;

@Component
public class SuperAdminScopes {

    public static final String OTHERS = "others";

    static final String TENANT_ORIGIN = "(SELECT lower(b2.created_by) FROM activation_codes c2 "
            + "JOIN code_batches b2 ON b2.id = c2.batch_id WHERE c2.tenant_id = t.id "
            + "ORDER BY c2.redeemed_at ASC NULLS LAST LIMIT 1)";

    public record Viewer(String email, boolean owner, boolean others) {}

    private final SuperAdminProperties config;
    private final JdbcTemplate jdbc;

    public SuperAdminScopes(SuperAdminProperties config, JdbcTemplate jdbc) {
        this.config = config;
        this.jdbc = jdbc;
    }

    public Viewer viewer(Authentication auth, String scope) {
        String email = auth.getName().toLowerCase();
        boolean owner = config.enabled() && email.equals(config.email());
        return new Viewer(email, owner, owner && OTHERS.equals(scope));
    }

    private static final String OTHER_ADMINS = "(SELECT lower(email) FROM superadmin_accounts WHERE lower(email) <> ?)";

    public String tenantFilter(Viewer viewer, List<Object> args) {
        args.add(viewer.email());
        if (!viewer.owner()) {
            return " AND " + TENANT_ORIGIN + " = ?";
        }
        return viewer.others() ? " AND " + TENANT_ORIGIN + " IN " + OTHER_ADMINS
                : " AND (" + TENANT_ORIGIN + " IS NULL OR " + TENANT_ORIGIN + " NOT IN " + OTHER_ADMINS + ")";
    }

    public String authorFilter(Viewer viewer, String column, List<Object> args) {
        args.add(viewer.email());
        if (!viewer.owner()) {
            return " AND lower(" + column + ") = ?";
        }
        return viewer.others() ? " AND lower(" + column + ") IN " + OTHER_ADMINS
                : " AND (" + column + " IS NULL OR lower(" + column + ") NOT IN " + OTHER_ADMINS + ")";
    }

    private static final String REGION_OF_ACCOUNT = "EXISTS (SELECT 1 FROM superadmin_accounts a WHERE a.enabled "
            + "AND ',' || a.regions || ',' LIKE '%%,' || %s || ',%%' AND lower(a.email) %s ?)";

    public String regionFilter(Viewer viewer, String column, List<Object> args) {
        args.add(viewer.email());
        if (!viewer.owner()) {
            return " AND " + REGION_OF_ACCOUNT.formatted(column, "=");
        }
        String others = REGION_OF_ACCOUNT.formatted(column, "<>");
        return viewer.others() ? " AND " + others : " AND NOT " + others;
    }

    public boolean canSeeRegion(Viewer viewer, String region) {
        if (viewer.owner()) {
            return true;
        }
        Boolean mine = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM superadmin_accounts a WHERE a.enabled "
                + "AND ',' || a.regions || ',' LIKE '%,' || ? || ',%' AND lower(a.email) = ?)",
                Boolean.class, region, viewer.email());
        return Boolean.TRUE.equals(mine);
    }

    public void requireTenant(Viewer viewer, UUID tenantId) {
        if (viewer.owner()) {
            return;
        }
        Boolean mine = jdbc.queryForObject("SELECT " + TENANT_ORIGIN.replace("t.id", "?") + " = ?", Boolean.class,
                tenantId, viewer.email());
        if (!Boolean.TRUE.equals(mine)) {
            throw ApiException.notFound("Negocio no encontrado");
        }
    }

    public void requireBatchOfCode(Viewer viewer, UUID codeId) {
        if (viewer.owner()) {
            return;
        }
        Boolean mine = jdbc.queryForObject("SELECT lower(b.created_by) = ? FROM activation_codes c "
                + "JOIN code_batches b ON b.id = c.batch_id WHERE c.id = ?", Boolean.class, viewer.email(), codeId);
        if (!Boolean.TRUE.equals(mine)) {
            throw ApiException.notFound("Código no encontrado");
        }
    }
}
