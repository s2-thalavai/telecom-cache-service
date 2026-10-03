package com.telecom.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class CacheInvalidationPublisher {

    public static final String CHANNEL = "telecom:cache:invalidation";
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instanceId;

    public CacheInvalidationPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, String instanceId) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
    }

    public void keyChanged(String cacheName, String key) {
        send(new CacheInvalidationMessage(instanceId, cacheName, key, false));
    }

    public void cacheCleared(String cacheName) {
        send(new CacheInvalidationMessage(instanceId, cacheName, null, true));
    }

    private void send(CacheInvalidationMessage message) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            // Pub/sub is best effort; the short L1 TTL is the safety net
            log.warn("Could not publish cache invalidation for {}:{} - {}", message.cacheName(), message.key(), e.getMessage());
        }
    }
}
