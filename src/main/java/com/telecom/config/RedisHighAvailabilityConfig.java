package com.telecom.config;

import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Extra Lettuce tuning for Redis Sentinel / Redis Cluster.
 * The topology itself (sentinel master + nodes, cluster nodes) comes from
 * application-sentinel.yml / application-cluster.yml via Spring Boot auto-configuration.
 */
@Configuration
@Profile("sentinel | cluster")
public class RedisHighAvailabilityConfig {

    /**
     * upstream (master)  = read-your-writes consistency (default, safest for caches).
     * replicaPreferred   = offload reads to replicas, but a read right after a write
     *                      can see the old value because of replication lag.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer telecomReadFromCustomizer(
            @Value("${telecom.cache.read-from:upstream}") String readFrom) {
        return builder -> builder.readFrom(ReadFrom.valueOf(readFrom));
    }
}
