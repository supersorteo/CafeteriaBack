package Cafeteria.coupon;

import java.sql.Date;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Cafeteria.plan.Entitlements;
import Cafeteria.shared.ApiException;
import Cafeteria.tenant.Tenant;
import Cafeteria.tenant.TenantRepository;

@Service
@Transactional
public class CouponService {

    public static final int MAX_STORED = 100;

    public record Coupon(UUID id, String code, String type, String title, String benefit, String validityText,
            LocalDate startsOn, LocalDate endsOn, String weekdays, LocalTime timeFrom, LocalTime timeTo,
            Integer maxRedemptions, int redemptions, boolean featured, boolean active, int sortOrder, long version) {}

    public record Input(String code, String type, String title, String benefit, String validityText, LocalDate startsOn,
            LocalDate endsOn, String weekdays, LocalTime timeFrom, LocalTime timeTo, Integer maxRedemptions,
            Boolean featured, Boolean active, Long version) {}

    private static final String COLUMNS = "id, code, type, title, benefit, validity_text, starts_on, ends_on, weekdays, "
            + "time_from, time_to, max_redemptions, redemptions, featured, active, sort_order, version";

    private static final RowMapper<Coupon> MAPPER = (rs, i) -> {
        Date starts = rs.getDate("starts_on");
        Date ends = rs.getDate("ends_on");
        Time from = rs.getTime("time_from");
        Time to = rs.getTime("time_to");
        Integer max = rs.getObject("max_redemptions", Integer.class);
        return new Coupon(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("type"), rs.getString("title"),
                rs.getString("benefit"), rs.getString("validity_text"), starts == null ? null : starts.toLocalDate(),
                ends == null ? null : ends.toLocalDate(), rs.getString("weekdays"), from == null ? null : from.toLocalTime(),
                to == null ? null : to.toLocalTime(), max, rs.getInt("redemptions"),
                rs.getBoolean("featured"), rs.getBoolean("active"), rs.getInt("sort_order"), rs.getLong("version"));
    };

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final Entitlements entitlements;

    public CouponService(JdbcTemplate jdbc, TenantRepository tenants, Entitlements entitlements) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<Coupon> list(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupons WHERE tenant_id = ? ORDER BY featured DESC, sort_order, created_at, id",
                MAPPER, tenantId);
    }

    public Coupon create(UUID tenantId, Input in) {
        Tenant tenant = lock(tenantId);
        Entitlements.Limits limits = entitlements.limitsOf(tenant);
        Input input = validate(in, limits);
        Long stored = jdbc.queryForObject("SELECT count(*) FROM coupons WHERE tenant_id = ?", Long.class, tenantId);
        if (stored != null && stored >= MAX_STORED) {
            throw ApiException.conflict("limit_reached", "Se alcanzó el máximo de " + MAX_STORED + " cupones guardados. Elimina los que ya no uses.");
        }
        LocalDate today = Entitlements.today(tenant);
        if (counts(input, 0, today, input.active() == null || input.active())
                && entitlements.activeCoupons(tenantId, today) >= limits.maxActiveCoupons()) {
            throw Entitlements.limitReached("cupones activos a la vez", limits.maxActiveCoupons());
        }
        UUID id = UUID.randomUUID();
        Integer next = jdbc.queryForObject("SELECT coalesce(max(sort_order), -1) + 1 FROM coupons WHERE tenant_id = ?",
                Integer.class, tenantId);
        try {
            jdbc.update("INSERT INTO coupons (id, tenant_id, code, type, title, benefit, validity_text, starts_on, ends_on, "
                    + "weekdays, time_from, time_to, max_redemptions, featured, active, sort_order) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenantId, input.code(), input.type(),
                    input.title(), input.benefit(), input.validityText(), input.startsOn(), input.endsOn(), input.weekdays(),
                    input.timeFrom(), input.timeTo(), input.maxRedemptions(), Boolean.TRUE.equals(input.featured()),
                    input.active() == null || input.active(), next == null ? 0 : next);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        return find(tenantId, id);
    }

    public Coupon update(UUID tenantId, UUID id, Input in) {
        Tenant tenant = lock(tenantId);
        Entitlements.Limits limits = entitlements.limitsOf(tenant);
        Input input = validate(in, limits);
        Coupon current = find(tenantId, id);
        if (input.version() == null) {
            throw ApiException.badRequest("version_required", "Falta la versión del registro");
        }
        if (input.version() != current.version()) {
            throw ApiException.conflict("version_conflict",
                    "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
        }
        LocalDate today = Entitlements.today(tenant);
        boolean wasCounted = counts(current.endsOn(), current.maxRedemptions(), current.redemptions(), today, current.active());
        boolean willCount = counts(input, current.redemptions(), today, input.active() == null || input.active());
        if (willCount && !wasCounted && entitlements.activeCoupons(tenantId, today) >= limits.maxActiveCoupons()) {
            throw Entitlements.limitReached("cupones activos a la vez", limits.maxActiveCoupons());
        }
        int updated;
        try {
            updated = jdbc.update("UPDATE coupons SET code = ?, type = ?, title = ?, benefit = ?, validity_text = ?, starts_on = ?, "
                    + "ends_on = ?, weekdays = ?, time_from = ?, time_to = ?, max_redemptions = ?, featured = ?, active = ?, "
                    + "version = version + 1, updated_at = now() WHERE id = ? AND tenant_id = ? AND version = ?",
                    input.code(), input.type(), input.title(), input.benefit(), input.validityText(), input.startsOn(),
                    input.endsOn(), input.weekdays(), input.timeFrom(), input.timeTo(), input.maxRedemptions(),
                    Boolean.TRUE.equals(input.featured()), input.active() == null || input.active(), id, tenantId,
                    current.version());
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        if (updated == 0) {
            throw ApiException.conflict("version_conflict", "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
        }
        return find(tenantId, id);
    }

    public void delete(UUID tenantId, UUID id) {
        lock(tenantId);
        if (jdbc.update("DELETE FROM coupons WHERE id = ? AND tenant_id = ?", id, tenantId) == 0) {
            throw ApiException.notFound("Cupón no encontrado");
        }
    }

    public Coupon redeem(UUID tenantId, UUID id) {
        Tenant tenant = lock(tenantId);
        if (!entitlements.limitsOf(tenant).advancedCoupons()) {
            throw Entitlements.featureUnavailable("El contador de canjes");
        }
        find(tenantId, id);
        int updated = jdbc.update("UPDATE coupons SET redemptions = redemptions + 1, version = version + 1, updated_at = now() "
                + "WHERE id = ? AND tenant_id = ? AND (max_redemptions IS NULL OR redemptions < max_redemptions)", id, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("coupon_exhausted", "Este cupón ya alcanzó su límite de usos");
        }
        return find(tenantId, id);
    }

    @Transactional(readOnly = true)
    public List<PublicCoupon> publicCoupons(Tenant tenant, Entitlements.Limits limits) {
        LocalDateTime now = LocalDateTime.now(Entitlements.zone(tenant));
        boolean advanced = limits.advancedCoupons();
        List<Coupon> visible = new ArrayList<>(jdbc.query("SELECT " + COLUMNS + " FROM coupons WHERE tenant_id = ? AND active "
                + "ORDER BY sort_order, created_at, id", MAPPER, tenant.getId()).stream()
                .filter(c -> isValidAt(c, now, advanced)).toList());
        visible.sort(Comparator.comparing((Coupon c) -> !(advanced && c.featured())));
        return visible.stream().limit(limits.maxActiveCoupons()).map(c -> new PublicCoupon(c.id(), c.code(), c.type(), c.title(),
                c.benefit(), c.validityText(), c.endsOn(), advanced ? schedule(c) : null, advanced && c.featured(),
                advanced && c.maxRedemptions() != null ? Math.max(c.maxRedemptions() - c.redemptions(), 0) : null))
                .collect(Collectors.toList());
    }

    public record PublicCoupon(UUID id, String code, String type, String title, String benefit, String validity,
            LocalDate endsOn, String schedule, boolean featured, Integer remaining) {}

    public static String status(Coupon c, LocalDate today) {
        if (!c.active()) {
            return "INACTIVE";
        }
        if (c.startsOn() != null && c.startsOn().isAfter(today)) {
            return "SCHEDULED";
        }
        if (c.endsOn() != null && c.endsOn().isBefore(today)) {
            return "EXPIRED";
        }
        if (c.maxRedemptions() != null && c.redemptions() >= c.maxRedemptions()) {
            return "EXHAUSTED";
        }
        return "ACTIVE";
    }

    static boolean isValidAt(Coupon c, LocalDateTime now, boolean advanced) {
        LocalDate today = now.toLocalDate();
        if (c.startsOn() != null && c.startsOn().isAfter(today)) {
            return false;
        }
        if (c.endsOn() != null && c.endsOn().isBefore(today)) {
            return false;
        }
        if (!advanced) {
            return true;
        }
        if (c.maxRedemptions() != null && c.redemptions() >= c.maxRedemptions()) {
            return false;
        }
        if (c.weekdays() != null && !c.weekdays().isEmpty()
                && c.weekdays().indexOf(Character.forDigit(now.getDayOfWeek().getValue(), 10)) < 0) {
            return false;
        }
        if (c.timeFrom() != null && c.timeTo() != null) {
            LocalTime time = now.toLocalTime();
            boolean inside = !c.timeFrom().isAfter(c.timeTo())
                    ? !time.isBefore(c.timeFrom()) && !time.isAfter(c.timeTo())
                    : !time.isBefore(c.timeFrom()) || !time.isAfter(c.timeTo());
            return inside;
        }
        return true;
    }

    static String schedule(Coupon c) {
        List<String> parts = new ArrayList<>();
        if (c.weekdays() != null && !c.weekdays().isEmpty() && c.weekdays().length() < 7) {
            String[] names = { "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom" };
            parts.add(c.weekdays().chars().mapToObj(ch -> names[ch - '1']).collect(Collectors.joining(", ")));
        }
        if (c.timeFrom() != null && c.timeTo() != null) {
            parts.add(c.timeFrom() + " a " + c.timeTo());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private boolean counts(Input in, int redemptions, LocalDate today, boolean active) {
        return counts(in.endsOn(), in.maxRedemptions(), redemptions, today, active);
    }

    private boolean counts(LocalDate endsOn, Integer max, int redemptions, LocalDate today, boolean active) {
        return active && (endsOn == null || !endsOn.isBefore(today)) && (max == null || redemptions < max);
    }

    private Input validate(Input in, Entitlements.Limits limits) {
        String code = in.code() == null ? "" : in.code().strip().toUpperCase();
        if (in.startsOn() != null && in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) {
            throw ApiException.invalidField("endsOn", "La fecha de fin no puede ser anterior a la de inicio");
        }
        if ((in.timeFrom() == null) != (in.timeTo() == null)) {
            throw ApiException.invalidField("timeFrom", "Indica la hora de inicio y la de fin");
        }
        String weekdays = in.weekdays() == null || in.weekdays().isBlank() ? null
                : in.weekdays().chars().distinct().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint,
                        StringBuilder::append).toString();
        boolean usesAdvanced = weekdays != null || in.timeFrom() != null || in.maxRedemptions() != null
                || Boolean.TRUE.equals(in.featured());
        if (usesAdvanced && !limits.advancedCoupons()) {
            throw Entitlements.featureUnavailable("Los cupones con días, horario, límite de usos y destacado");
        }
        String validity = in.validityText() == null || in.validityText().isBlank() ? null : in.validityText().strip();
        return new Input(code, in.type(), in.title().strip(), in.benefit().strip(), validity, in.startsOn(), in.endsOn(),
                weekdays, in.timeFrom(), in.timeTo(), in.maxRedemptions(), in.featured(), in.active(), in.version());
    }

    private Tenant lock(UUID tenantId) {
        return tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
    }

    private Coupon find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM coupons WHERE id = ? AND tenant_id = ?", MAPPER, id, tenantId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("Cupón no encontrado"));
    }

    private static ApiException duplicate() {
        return ApiException.conflict("duplicate_coupon_code", "Ya tienes un cupón con ese código");
    }
}
