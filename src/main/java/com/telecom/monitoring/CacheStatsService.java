package com.telecom.monitoring;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.telecom.cache.TwoLevelCache;
import com.telecom.exception.ResourceNotFoundException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Provider-independent view of cache effectiveness (hit ratio, size, evictions)
 * for Caffeine, Redis and the custom two-level cache.
 */
@Service
public class CacheStatsService {

    private final CacheManager cacheManager;

    public CacheStatsService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public List<CacheStatsView> allStats() {
        return cacheManager.getCacheNames().stream().sorted().map(this::stats).toList();
    }

    public CacheStatsView stats(String cacheName) {
        Cache target = unwrap(requireCache(cacheName));

        if (target instanceof CaffeineCache caffeine) {
            CacheStats s = caffeine.getNativeCache().stats();
            return new CacheStatsView(cacheName, "caffeine", s.hitCount(), s.missCount(),
                    ratio(s.hitCount(), s.missCount()), caffeine.getNativeCache().estimatedSize(),
                    Map.of("evictions", s.evictionCount(), "loadFailures", s.loadFailureCount()));
        }
        if (target instanceof RedisCache redis) {
            CacheStatistics s = redis.getStatistics();
            return new CacheStatsView(cacheName, "redis", s.getHits(), s.getMisses(),
                    ratio(s.getHits(), s.getMisses()), null,
                    Map.of("puts", s.getPuts(), "deletes", s.getDeletes()));
        }
        if (target instanceof TwoLevelCache twoLevel) {
            long hits = twoLevel.l1Hits() + twoLevel.l2Hits();
            return new CacheStatsView(cacheName, "caffeine+redis", hits, twoLevel.misses(),
                    ratio(hits, twoLevel.misses()), null,
                    Map.of("l1Hits", twoLevel.l1Hits(), "l2Hits", twoLevel.l2Hits()));
        }
        return new CacheStatsView(cacheName, target.getClass().getSimpleName(), 0, 0, 0, null, Map.of());
    }

    public void clear(String cacheName) {
        requireCache(cacheName).clear();
    }

    public void evict(String cacheName, String key) {
        requireCache(cacheName).evict(key);
    }

    private Cache requireCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new ResourceNotFoundException("Cache " + cacheName + " not found");
        }
        return cache;
    }

    private static Cache unwrap(Cache cache) {
        return cache instanceof TransactionAwareCacheDecorator decorator ? decorator.getTargetCache() : cache;
    }

    private static double ratio(long hits, long misses) {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
