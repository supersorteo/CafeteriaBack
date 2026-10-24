package Cafeteria.mail;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmailOutbox {

    public record Row(UUID id, String type, String recipient, String payload, int attempts) {}

    private final JdbcTemplate jdbc;

    public EmailOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String type, String recipient, String payload) {
        jdbc.update("INSERT INTO email_outbox (id, type, recipient, payload) VALUES (?,?,?,?)",
                UUID.randomUUID(), type, recipient, payload);
    }

    public boolean recentlyQueued(String type, String recipient, int seconds) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE type = ? AND recipient = ? "
                + "AND created_at > now() - make_interval(secs => ?::double precision)",
                Integer.class, type, recipient, seconds);
        return count != null && count > 0;
    }

    public List<Row> claimDue(int limit) {
        return jdbc.query("SELECT id, type, recipient, payload, attempts FROM email_outbox "
                + "WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT ? "
                + "FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("recipient"),
                        rs.getString("payload"), rs.getInt("attempts")),
                limit);
    }

    public void markSent(UUID id) {
        jdbc.update("UPDATE email_outbox SET status = 'SENT', payload = NULL, sent_at = now() WHERE id = ?", id);
    }

    public void markAttemptFailed(Row row, int maxAttempts) {
        int attempts = row.attempts() + 1;
        if (attempts >= maxAttempts) {
            jdbc.update("UPDATE email_outbox SET status = 'FAILED', payload = NULL, attempts = ? WHERE id = ?",
                    attempts, row.id());
            return;
        }
        long backoffSeconds = Math.min(60L * (1L << Math.min(attempts, 6)), 3600L);
        jdbc.update("UPDATE email_outbox SET attempts = ?, next_attempt_at = now() + make_interval(secs => ?::double precision) "
                + "WHERE id = ?", attempts, backoffSeconds, row.id());
    }
}
