package Cafeteria.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.worker.enabled", havingValue = "true")
public class OutboxScheduler {

    private final OutboxProcessor processor;

    public OutboxScheduler(OutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${outbox.worker.delay-ms:5000}")
    void run() {
        processor.processBatch();
    }
}
