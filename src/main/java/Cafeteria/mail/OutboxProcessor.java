package Cafeteria.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH_SIZE = 10;

    private final EmailOutbox outbox;
    private final EmailSender sender;
    private final EmailTemplates templates;
    private final TransactionTemplate tx;
    private final int maxAttempts;

    public OutboxProcessor(EmailOutbox outbox, EmailSender sender, EmailTemplates templates, TransactionTemplate tx,
            @Value("${outbox.max-attempts:8}") int maxAttempts) {
        this.outbox = outbox;
        this.sender = sender;
        this.templates = templates;
        this.tx = tx;
        this.maxAttempts = maxAttempts;
    }

    public int processBatch() {
        Integer sent = tx.execute(status -> {
            int ok = 0;
            for (EmailOutbox.Row row : outbox.claimDue(BATCH_SIZE)) {
                try {
                    sender.send(templates.build(row.type(), row.recipient(), row.payload()));
                    outbox.markSent(row.id());
                    ok++;
                } catch (RuntimeException e) {
                    log.warn("Fallo al enviar correo {} (intento {}): {}", row.id(), row.attempts() + 1,
                            e.getClass().getSimpleName());
                    outbox.markAttemptFailed(row, maxAttempts);
                }
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }
}
