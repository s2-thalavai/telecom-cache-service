package com.telecom.cache;

/**
 * Broadcast over Redis pub/sub. "origin" lets a pod ignore its own messages.
 */
public record CacheInvalidationMessage(String origin, String cacheName, String key, boolean clearAll) {
}
