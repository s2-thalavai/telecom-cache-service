package com.telecom.config;

import java.util.List;

/**
 * Cache names act as cache "regions". Each region has its own TTL, size limit,
 * serializer and Redis key namespace (see TelecomCacheProperties#keyPrefix).
 */
public final class CacheNames {

    public static final String TARIFF_PLANS = "tariff-plans";             // planId -> TariffPlanDto
    public static final String PLAN_CATALOG = "plan-catalog";             // 'ACTIVE' -> List<TariffPlanDto>
    public static final String SUBSCRIBERS = "subscribers";               // msisdn -> SubscriberDto
    public static final String SUBSCRIBER_DETAILS = "subscriber-details"; // msisdn -> subscriber + plan

    public static final List<String> ALL = List.of(TARIFF_PLANS, PLAN_CATALOG, SUBSCRIBERS, SUBSCRIBER_DETAILS);

    private CacheNames() {
    }
}
