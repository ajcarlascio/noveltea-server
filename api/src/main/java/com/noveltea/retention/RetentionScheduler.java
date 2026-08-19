package com.noveltea.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the retention sweep on a timer.
 *
 * <p>Separated from the service so tests can drive the sweep directly without a scheduler,
 * and so an operator can disable the timer without losing the ability to purge by hand.
 */
@Component
@ConditionalOnProperty(value = "noveltea.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retention;

    public RetentionScheduler(RetentionService retention) {
        this.retention = retention;
    }

    /** Hourly, offset from the hour so it does not land with every other cron on the box. */
    @Scheduled(cron = "0 17 * * * *")
    public void sweep() {
        try {
            RetentionService.SweepReport report = retention.sweep();
            if (report.total() > 0) {
                log.info("retention sweep removed {}", report);
            }
        } catch (Exception e) {
            // A failed sweep must never take the application down; it runs again next hour.
            log.error("retention sweep failed", e);
        }
    }
}
