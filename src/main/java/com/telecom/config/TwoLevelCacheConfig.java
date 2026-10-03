package com.telecom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.cache.CacheInvalidationListener;
import com.telecom.cache.CacheInvalidationPublisher;
import com.telecom.cache.TwoLevelCache;
import com.telecom.cache.TwoLevelCacheManager;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.UUID;

/**
 * "two-level" profile (use together with "redis"):
 * L1 = Caffeine in each pod (microsecond reads), L2 = Redis shared by all pods,
 * Redis pub/sub broadcasts invalidations so every pod drops stale L1 entries.
 */
@Configuration
@Profile("two-level")
public class TwoLevelCacheConfig {

    private final String instanceId = UUID.randomUUID().toString();

    @Bean
    public CacheInvalidationPublisher cacheInvalidationPublisher(StringRedisTemplate redisTemplate,
                                                                 ObjectMapper objectMapper) {
        return new CacheInvalidationPublisher(redisTemplate, objectMapper, instanceId);
    }

    @Bean
    public TwoLevelCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                             TelecomCacheProperties props,
                                             CacheInvalidationPublisher publisher) {
        RedisCacheManager remote = RedisCacheConfig.buildRedisCacheManager(connectionFactory, props, false);
        remote.afterPropertiesSet();
        CaffeineCacheManager local = CaffeineCacheConfig.buildCaffeineCacheManager(props, props.l1Ttl());

        TwoLevelCacheManager manager = new TwoLevelCacheManager(local, remote, publisher);
        manager.setTransactionAware(true);
        return manager;
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
                                                                            TwoLevelCacheManager cacheManager,
                                                                            ObjectMapper objectMapper) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                new CacheInvalidationListener(objectMapper, instanceId, cacheManager),
                new ChannelTopic(CacheInvalidationPublisher.CHANNEL));
        return container;
    }

    /** Exposes telecom.cache.two.level.gets{cache, result=l1_hit|l2_hit|miss}. */
    @Bean
    public MeterBinder twoLevelCacheMetrics(TwoLevelCacheManager cacheManager) {
        return registry -> cacheManager.twoLevelCaches().forEach((name, cache) -> {
            FunctionCounter.builder("telecom.cache.two.level.gets", cache, TwoLevelCache::l1Hits)
                    .tag("cache", name).tag("result", "l1_hit").register(registry);
            FunctionCounter.builder("telecom.cache.two.level.gets", cache, TwoLevelCache::l2Hits)
                    .tag("cache", name).tag("result", "l2_hit").register(registry);
            FunctionCounter.builder("telecom.cache.two.level.gets", cache, TwoLevelCache::misses)
                    .tag("cache", name).tag("result", "miss").register(registry);
        });
    }
}
