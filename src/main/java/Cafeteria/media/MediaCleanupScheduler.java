package Cafeteria.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "media.cleanup.enabled", havingValue = "true")
public class MediaCleanupScheduler {

    private final MediaCleanupService cleanup;

    public MediaCleanupScheduler(MediaCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${media.cleanup.delay-ms:3600000}", initialDelayString = "${media.cleanup.initial-delay-ms:300000}")
    void run() {
        cleanup.run();
    }
}
