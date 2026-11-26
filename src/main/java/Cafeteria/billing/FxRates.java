package Cafeteria.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FxRates {

    private final JdbcTemplate jdbc;

    public FxRates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Rate(BigDecimal rate, java.time.Instant updatedAt, String updatedBy) {}

    public Optional<Rate> latest() {
        return jdbc.query("SELECT rate, created_at, created_by FROM fx_rates WHERE base = 'USD' AND quote = 'ARS' ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> new Rate(rs.getBigDecimal(1), rs.getTimestamp(2).toInstant(), rs.getString(3))).stream().findFirst();
    }

    public Optional<BigDecimal> usdToArs() {
        return jdbc.query("SELECT rate FROM fx_rates WHERE base = 'USD' AND quote = 'ARS' ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal(1)).stream().findFirst();
    }

    public void record(BigDecimal rate, String source, String actor) {
        jdbc.update("INSERT INTO fx_rates (id, base, quote, rate, source, created_by) VALUES (?, 'USD', 'ARS', ?, ?, ?)",
                UUID.randomUUID(), rate, source, actor);
    }

    public static int pesos(int usdCents, BigDecimal rate) {
        BigDecimal pesos = BigDecimal.valueOf(usdCents).movePointLeft(2).multiply(rate);
        BigDecimal rounded = pesos.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
        return Math.max(10, rounded.intValueExact());
    }
}
