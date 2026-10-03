package com.telecom.monitoring;

import java.util.Map;

public record CacheStatsView(String cacheName, String provider, long hits, long misses,
                             double hitRatio, Long estimatedSize, Map<String, Long> details) {
}
