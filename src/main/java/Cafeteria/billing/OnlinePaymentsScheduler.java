package Cafeteria.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.worker.enabled", havingValue = "true")
public class OnlinePaymentsScheduler {

    private final OnlineOrderService online;

    public OnlinePaymentsScheduler(OnlineOrderService online) {
        this.online = online;
    }

    @Scheduled(fixedDelayString = "${billing.reconcile.delay-ms:300000}", initialDelayString = "${billing.reconcile.delay-ms:300000}")
    void reconcile() {
        online.reconcile();
    }
}
