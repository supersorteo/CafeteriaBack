package Cafeteria.plan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.worker.enabled", havingValue = "true")
public class PlanNoticeScheduler {

    private final PlanNoticeService notices;

    public PlanNoticeScheduler(PlanNoticeService notices) {
        this.notices = notices;
    }

    @Scheduled(fixedDelayString = "${plan.notices.delay-ms:3600000}", initialDelayString = "${plan.notices.initial-delay-ms:120000}")
    void send() {
        notices.sendDue();
    }
}
