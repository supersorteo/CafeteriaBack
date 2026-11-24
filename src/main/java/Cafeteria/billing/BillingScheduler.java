package Cafeteria.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.worker.enabled", havingValue = "true")
public class BillingScheduler {

    private final BillingService billing;

    public BillingScheduler(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(fixedDelayString = "${billing.expire.delay-ms:3600000}", initialDelayString = "${billing.expire.delay-ms:3600000}")
    void expire() {
        billing.expireOld();
    }
}
