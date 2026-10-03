package com.telecom.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom CacheManager implementing a "near cache" strategy:
 * every configured region becomes a TwoLevelCache (Caffeine L1 + Redis L2).
 * Extends AbstractTransactionSupportingCacheManager so writes can be deferred to after commit.
 */
public class TwoLevelCacheManager extends AbstractTransactionSupportingCacheManager {

    private final CaffeineCacheManager local;
    private final RedisCacheManager remote;
    private final CacheInvalidationPublisher publisher;
    private final Map<String, TwoLevelCache> twoLevelCaches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CaffeineCacheManager local, RedisCacheManager remote,
                                CacheInvalidationPublisher publisher) {
        this.local = local;
        this.remote = remote;
        this.publisher = publisher;
    }

    @Override
    protected Collection<? extends Cache> loadCaches() {
        List<Cache> caches = new ArrayList<>();
        for (String name : remote.getCacheNames()) {
            Cache l1 = local.getCache(name);
            Cache l2 = remote.getCache(name);
            if (l1 == null || l2 == null) {
                throw new IllegalStateException("Cache region '" + name + "' must exist in both L1 and L2");
            }
            TwoLevelCache cache = new TwoLevelCache(name, l1, l2, publisher);
            twoLevelCaches.put(name, cache);
            caches.add(cache);
        }
        return caches;
    }

    /** Called by the pub/sub listener when another pod changed a key. */
    public void evictLocal(String cacheName, String key) {
        Cache cache = local.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    public void clearLocal(String cacheName) {
        Cache cache = local.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    public Map<String, TwoLevelCache> twoLevelCaches() {
        return Collections.unmodifiableMap(twoLevelCaches);
    }
}
