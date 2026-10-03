package com.telecom;

import com.telecom.dto.SubscriberDto;
import com.telecom.dto.UpdatePricingRequest;
import com.telecom.repository.SubscriberRepository;
import com.telecom.service.SubscriberService;
import com.telecom.service.TariffPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Proves the caching behaviour (default profile: Caffeine + H2). */
@SpringBootTest
class SubscriberCachingTest {

    @Autowired
    private SubscriberService subscriberService;
    @Autowired
    private TariffPlanService tariffPlanService;
    @Autowired
    private CacheManager cacheManager;
    @SpyBean
    private SubscriberRepository subscriberRepository;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        clearInvocations(subscriberRepository);
    }

    @Test
    void secondLookupIsServedFromCache() {
        subscriberService.getSubscriber("9988776655");
        subscriberService.getSubscriber("9988776655");

        verify(subscriberRepository, times(1)).findById("9988776655");
    }

    @Test
    void cachePutKeepsCacheInSyncAfterPlanChange() {
        subscriberService.getSubscriber("9876543210");
        subscriberService.changePlan("9876543210", "POSTPAID_999");
        clearInvocations(subscriberRepository);

        SubscriberDto after = subscriberService.getSubscriber("9876543210");

        assertThat(after.planId()).isEqualTo("POSTPAID_999");
        verify(subscriberRepository, never()).findById("9876543210"); // served from @CachePut value
    }

    @Test
    void tariffChangeEvictsRelatedSubscriberDetails() {
        assertThat(subscriberService.getSubscriberDetails("9123456780").plan().monthlyFee())
                .isEqualByComparingTo("599.00");

        tariffPlanService.updatePricing("POSTPAID_599", new UpdatePricingRequest(new BigDecimal("649.00"), 120, 30));

        assertThat(subscriberService.getSubscriberDetails("9123456780").plan().monthlyFee())
                .isEqualByComparingTo("649.00");
    }
}
