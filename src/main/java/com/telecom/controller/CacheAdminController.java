package com.telecom.controller;

import com.telecom.monitoring.CacheStatsService;
import com.telecom.monitoring.CacheStatsView;
import com.telecom.service.TariffPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operations endpoints for cache monitoring and management.
 * In production protect /api/v1/admin/** with an ADMIN/OPS role (Spring Security) or
 * expose it only on the internal management network.
 */
@RestController
@RequestMapping("/api/v1/admin/caches")
public class CacheAdminController {

    private final CacheStatsService cacheStatsService;
    private final TariffPlanService tariffPlanService;

    public CacheAdminController(CacheStatsService cacheStatsService, TariffPlanService tariffPlanService) {
        this.cacheStatsService = cacheStatsService;
        this.tariffPlanService = tariffPlanService;
    }

    @GetMapping
    public List<CacheStatsView> all() {
        return cacheStatsService.allStats();
    }

    @GetMapping("/{cacheName}")
    public CacheStatsView one(@PathVariable String cacheName) {
        return cacheStatsService.stats(cacheName);
    }

    /** Evict one key, e.g. DELETE /api/v1/admin/caches/subscribers/keys/9876543210 */
    @DeleteMapping("/{cacheName}/keys/{key}")
    public ResponseEntity<Void> evict(@PathVariable String cacheName, @PathVariable String key) {
        cacheStatsService.evict(cacheName, key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{cacheName}")
    public ResponseEntity<Void> clear(@PathVariable String cacheName) {
        cacheStatsService.clear(cacheName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/plan-catalog/refresh")
    public ResponseEntity<Void> refreshCatalog() {
        tariffPlanService.refreshActiveCatalog();
        return ResponseEntity.accepted().build();
    }
}
