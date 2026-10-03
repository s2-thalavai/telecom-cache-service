package com.telecom.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

/**
 * Default profile: in-memory (per JVM) cache with Caffeine.
 * Good for a single instance or for reference data that tolerates per-pod staleness.
 */
@Configuration
@Profile("!redis & !two-level")
public class CaffeineCacheConfig {

    @Bean
    public CacheManager cacheManager(TelecomCacheProperties props) {
        // Transaction-aware: cache put/evict is applied only after the DB transaction commits
        return new TransactionAwareCacheManagerProxy(buildCaffeineCacheManager(props, null));
    }

    /**
     * Customised CacheManager: fixed set of regions (no accidental unbounded caches),
     * per-region size + TTL, null values rejected, statistics recorded for monitoring.
     *
     * @param ttlCap optional upper bound for TTL (used for the L1 tier of the two-level cache)
     */
    public static CaffeineCacheManager buildCaffeineCacheManager(TelecomCacheProperties props, Duration ttlCap) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.setCacheNames(List.of()); // static mode: unknown cache names are not auto-created

        for (String name : CacheNames.ALL) {
            Duration ttl = props.ttlFor(name);
            if (ttlCap != null && ttlCap.compareTo(ttl) < 0) {
                ttl = ttlCap;
            }
            manager.registerCustomCache(name, Caffeine.newBuilder()
                    .maximumSize(props.maxSizeFor(name))   // size-based eviction (W-TinyLFU)
                    .expireAfterWrite(ttl)                 // time-based expiration
                    .recordStats()                         // hit/miss/eviction metrics
                    .build());
        }
        return manager;
    }
}
