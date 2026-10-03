package com.telecom.config;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * If Redis is down or times out, log the error and fall through to the database
 * instead of failing the API call. The cache is an optimisation, not a dependency.
 */
@Configuration
public class CacheErrorHandlingConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }
}
