package com.telecom.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.io.IOException;

/**
 * Runs in every pod: drops the local (L1) entry that another pod has changed.
 */
public class CacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final TwoLevelCacheManager cacheManager;

    public CacheInvalidationListener(ObjectMapper objectMapper, String instanceId, TwoLevelCacheManager cacheManager) {
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.cacheManager = cacheManager;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CacheInvalidationMessage msg = objectMapper.readValue(message.getBody(), CacheInvalidationMessage.class);
            if (instanceId.equals(msg.origin())) {
                return; // this pod already updated its own L1
            }
            if (msg.clearAll()) {
                cacheManager.clearLocal(msg.cacheName());
                log.debug("L1 region {} cleared (remote request)", msg.cacheName());
            } else {
                cacheManager.evictLocal(msg.cacheName(), msg.key());
                log.debug("L1 key {}::{} evicted (remote request)", msg.cacheName(), msg.key());
            }
        } catch (IOException e) {
            log.warn("Ignoring malformed cache invalidation message: {}", e.getMessage());
        }
    }
}
