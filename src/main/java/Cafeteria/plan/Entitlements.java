package Cafeteria.plan;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import Cafeteria.seed.DemoSiteSeeder;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;

@Component
public class Entitlements {

    public static final String DEFAULT_PLAN = "PROFESIONAL";
    private static final int DEMO_LIMIT = 9999;
    public static final String COUPON_IS_ACTIVE = "active AND (ends_on IS NULL OR ends_on >= ?) "
            + "AND (max_redemptions IS NULL OR redemptions < max_redemptions)";

    public record Limits(String code, String name, int maxMenuItems, int maxActiveCoupons, int maxGallery, int maxTableQr,
            boolean fullBranding, boolean advancedCoupons, boolean tableQr, boolean prioritySupport, boolean stats) {}

    public record Usage(long menuItems, long activeCoupons, long gallery) {}

    private final JdbcTemplate jdbc;

    public Entitlements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Limits limitsOf(Tenant tenant) {
        if (DemoSiteSeeder.SLUG.equals(tenant.getSlug())) {
            Limits pro = limitsOf(DEFAULT_PLAN);
            return new Limits(pro.code(), pro.name(), DEMO_LIMIT, DEMO_LIMIT, pro.maxGallery(), pro.maxTableQr(),
                    true, true, true, true, true);
        }
        return limitsOf(tenant.getPlanCode());
    }

    public Limits limitsOf(String planCode) {
        String code = planCode == null || planCode.isBlank() ? DEFAULT_PLAN : planCode;
        return find(code).or(() -> find(DEFAULT_PLAN))
                .orElseThrow(() -> new IllegalStateException("Falta la configuración del plan " + DEFAULT_PLAN));
    }

    public boolean exists(String planCode) {
        return planCode != null && find(planCode).isPresent();
    }

    public Usage usageOf(Tenant tenant) {
        UUID id = tenant.getId();
        return new Usage(count("SELECT count(*) FROM menu_items WHERE tenant_id = ?", id),
                activeCoupons(id, today(tenant)),
                count("SELECT count(*) FROM site_images WHERE tenant_id = ? AND slot = 'GALLERY'", id));
    }

    public long activeCoupons(UUID tenantId, LocalDate today) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM coupons WHERE tenant_id = ? AND " + COUPON_IS_ACTIVE,
                Long.class, tenantId, today);
        return count == null ? 0 : count;
    }

    public static LocalDate today(Tenant tenant) {
        return LocalDate.now(zone(tenant));
    }

    public static ZoneId zone(Tenant tenant) {
        try {
            return ZoneId.of(tenant.getTimezone());
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    public static ApiException limitReached(String what, int max) {
        return new ApiException(HttpStatus.FORBIDDEN, "plan_limit_reached",
                "Tu plan permite hasta " + max + " " + what + ". Mejora tu plan para agregar más.");
    }

    public static ApiException featureUnavailable(String what) {
        return new ApiException(HttpStatus.FORBIDDEN, "plan_feature_unavailable",
                what + " está disponible en el plan Profesional.");
    }

    private long count(String sql, UUID tenantId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private java.util.Optional<Limits> find(String code) {
        return jdbc.query("SELECT code, name, max_menu_items, max_active_coupons, max_gallery, max_table_qr, full_branding, "
                + "advanced_coupons, table_qr, priority_support, stats FROM billing_plans WHERE code = ?",
                (rs, i) -> new Limits(rs.getString("code"), rs.getString("name"), rs.getInt("max_menu_items"),
                        rs.getInt("max_active_coupons"), rs.getInt("max_gallery"), rs.getInt("max_table_qr"),
                        rs.getBoolean("full_branding"), rs.getBoolean("advanced_coupons"), rs.getBoolean("table_qr"),
                        rs.getBoolean("priority_support"), rs.getBoolean("stats")), code).stream().findFirst();
    }
}
