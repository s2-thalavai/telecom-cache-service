package com.telecom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Externalised cache policy, bound from "telecom.cache.*" in application.yml.
 * Changing TTLs or sizes requires no code change.
 */
@ConfigurationProperties(prefix = "telecom.cache")
public record TelecomCacheProperties(
        String namespace,          // e.g. "telecom"  -> isolates this app's keys in a shared Redis
        String schemaVersion,      // e.g. "v1"       -> bump to invalidate everything after incompatible DTO changes
        Duration defaultTtl,
        Duration l1Ttl,            // local (Caffeine) TTL in the two-level profile
        Boolean useScanForClear,   // SCAN instead of KEYS for cache.clear() on Redis
        Map<String, CacheSpec> caches) {

    public TelecomCacheProperties {
        namespace = (namespace == null || namespace.isBlank()) ? "telecom" : namespace;
        schemaVersion = (schemaVersion == null || schemaVersion.isBlank()) ? "v1" : schemaVersion;
        defaultTtl = defaultTtl == null ? Duration.ofMinutes(10) : defaultTtl;
        l1Ttl = l1Ttl == null ? Duration.ofSeconds(30) : l1Ttl;
        useScanForClear = useScanForClear == null ? Boolean.TRUE : useScanForClear;
        caches = caches == null ? Map.of() : Map.copyOf(caches);
    }

    public record CacheSpec(Duration ttl, Long maxSize) {
    }

    public Duration ttlFor(String cacheName) {
        CacheSpec spec = caches.get(cacheName);
        return spec != null && spec.ttl() != null ? spec.ttl() : defaultTtl;
    }

    public long maxSizeFor(String cacheName) {
        CacheSpec spec = caches.get(cacheName);
        return spec != null && spec.maxSize() != null ? spec.maxSize() : 10_000L;
    }

    /** Redis key namespace per region, e.g. "telecom:v1:subscribers::" + "9876543210". */
    public String keyPrefix(String cacheName) {
        return namespace + ":" + schemaVersion + ":" + cacheName + "::";
    }
}
