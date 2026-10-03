package com.telecom.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.BatchStrategy;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * "redis" profile: one shared, distributed cache for all API instances.
 */
@Configuration
@Profile("redis & !two-level")
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, TelecomCacheProperties props) {
        return buildRedisCacheManager(connectionFactory, props, true);
    }

    public static RedisCacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory,
                                                           TelecomCacheProperties props,
                                                           boolean transactionAware) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(props.defaultTtl())                 // fallback expiration
                .disableCachingNullValues()
                .computePrefixWith(props::keyPrefix)          // namespace: telecom:v1:<region>::
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()));

        // Region-specific TTL + typed JSON serializer
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        CacheValueSerializers.perCache().forEach((name, serializer) ->
                perCache.put(name, defaults
                        .entryTtl(props.ttlFor(name))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))));

        // cache.clear() uses KEYS by default, which blocks Redis. SCAN is safe in production.
        BatchStrategy clearStrategy = props.useScanForClear() ? BatchStrategies.scan(500) : BatchStrategies.keys();
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, clearStrategy);

        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(writer)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()   // only the configured regions exist
                .enableStatistics();             // hits/misses/puts/deletes for Actuator + Micrometer
        if (transactionAware) {
            builder.transactionAware();          // writes applied after DB commit
        }
        return builder.build();
    }
}
