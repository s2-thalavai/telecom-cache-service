package com.telecom.cache;

import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;

/**
 * L1 (Caffeine, per pod) in front of L2 (Redis, shared).
 * Reads:  L1 -> L2 -> loader (DB). An L2 hit back-fills L1.
 * Writes: L2 first, then L1, then broadcast so other pods evict their L1 copy.
 * Keys are expected to be Strings (MSISDN, planId, 'ACTIVE').
 */
public class TwoLevelCache implements Cache {

    private final String name;
    private final Cache local;
    private final Cache remote;
    private final CacheInvalidationPublisher publisher;

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public TwoLevelCache(String name, Cache local, Cache remote, CacheInvalidationPublisher publisher) {
        this.name = name;
        this.local = local;
        this.remote = remote;
        this.publisher = publisher;
    }

    private static String localKey(Object key) {
        return String.valueOf(key);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper value = local.get(localKey(key));
        if (value != null) {
            l1Hits.increment();
            return value;
        }
        value = remote.get(key);
        if (value != null) {
            l2Hits.increment();
            local.put(localKey(key), value.get());
            return value;
        }
        misses.increment();
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    /** Used by @Cacheable(sync = true): only one thread per pod loads a missing key. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        String k = localKey(key);
        ValueWrapper cached = local.get(k);
        if (cached != null) {
            l1Hits.increment();
            return (T) cached.get();
        }
        return local.get(k, () -> {
            ValueWrapper fromRedis = remote.get(key);
            if (fromRedis != null) {
                l2Hits.increment();
                return (T) fromRedis.get();
            }
            misses.increment();
            T loaded = valueLoader.call();
            if (loaded != null) {
                remote.put(key, loaded);
            }
            return loaded;
        });
    }

    @Override
    public void put(Object key, Object value) {
        remote.put(key, value);
        local.put(localKey(key), value);
        publisher.keyChanged(name, localKey(key));
    }

    @Override
    public void evict(Object key) {
        remote.evict(key);
        local.evict(localKey(key));
        publisher.keyChanged(name, localKey(key));
    }

    @Override
    public void clear() {
        remote.clear();
        local.clear();
        publisher.cacheCleared(name);
    }

    public long l1Hits() {
        return l1Hits.sum();
    }

    public long l2Hits() {
        return l2Hits.sum();
    }

    public long misses() {
        return misses.sum();
    }
}
