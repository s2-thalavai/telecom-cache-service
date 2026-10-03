package com.telecom.cache;

import com.telecom.service.TariffPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Warm-up and proactive refresh of the plan catalogue (read by every self-care app launch).
 * Uses @CachePut through the service proxy, so readers never see an empty cache.
 */
@Component
public class CatalogCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheRefresher.class);

    private final TariffPlanService tariffPlanService;

    public CatalogCacheRefresher(TariffPlanService tariffPlanService) {
        this.tariffPlanService = tariffPlanService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        int size = tariffPlanService.refreshActiveCatalog().size();
        log.info("Plan catalogue cache warmed with {} plans", size);
    }

    // With several pods sharing Redis, guard this with ShedLock so only one pod refreshes.
    @Scheduled(cron = "${telecom.cache.catalog-refresh-cron:0 */10 * * * *}")
    public void scheduledRefresh() {
        tariffPlanService.refreshActiveCatalog();
        log.debug("Plan catalogue cache refreshed on schedule");
    }
}
