package com.telecom.cache;

import com.telecom.config.CacheNames;
import com.telecom.event.TariffPlanChangedEvent;
import com.telecom.repository.SubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Cross-region consistency: "subscriber-details" embeds a copy of the tariff plan.
 * When a plan changes, every cached subscriber-details entry on that plan is stale,
 * so evict them - but only AFTER the plan update has committed.
 */
@Component
public class RelatedCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RelatedCacheInvalidator.class);

    private final SubscriberRepository subscriberRepository;
    private final CacheManager cacheManager;

    public RelatedCacheInvalidator(SubscriberRepository subscriberRepository, CacheManager cacheManager) {
        this.subscriberRepository = subscriberRepository;
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTariffPlanChanged(TariffPlanChangedEvent event) {
        Cache details = cacheManager.getCache(CacheNames.SUBSCRIBER_DETAILS);
        if (details == null) {
            return;
        }
        List<String> msisdns = subscriberRepository.findMsisdnsByPlanId(event.planId());
        msisdns.forEach(details::evict);
        log.info("Plan {} changed -> evicted {} related subscriber-details entries", event.planId(), msisdns.size());
    }
}
