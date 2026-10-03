package com.telecom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching              // switches on the @Cacheable / @CachePut / @CacheEvict proxies
@EnableScheduling           // scheduled cache refresh (CatalogCacheRefresher)
@ConfigurationPropertiesScan
public class TelecomCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelecomCacheApplication.class, args);
    }
}
