package com.telecom.controller;

import com.telecom.dto.CreateTariffPlanRequest;
import com.telecom.dto.TariffPlanDto;
import com.telecom.dto.UpdatePricingRequest;
import com.telecom.service.TariffPlanService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Public plan catalogue: server-side cache (Spring Cache) + HTTP cache (Cache-Control/ETag)
 * so apps, CDN and API gateway can also cache it.
 */
@RestController
@RequestMapping("/api/v1/plans")
public class TariffPlanController {

    private final TariffPlanService service;

    public TariffPlanController(TariffPlanService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TariffPlanDto>> catalog() {
        List<TariffPlanDto> plans = service.getActiveCatalog();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .eTag("\"catalog-" + Integer.toHexString(plans.hashCode()) + "\"")
                .body(plans);
    }

    /** If-None-Match with the current version -> Spring answers 304 automatically. */
    @GetMapping("/{planId}")
    public ResponseEntity<TariffPlanDto> plan(@PathVariable String planId) {
        TariffPlanDto plan = service.getPlan(planId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .eTag("\"" + plan.version() + "\"")
                .body(plan);
    }

    @PostMapping
    public ResponseEntity<TariffPlanDto> create(@Valid @RequestBody CreateTariffPlanRequest request) {
        TariffPlanDto created = service.createPlan(request);
        return ResponseEntity.created(URI.create("/api/v1/plans/" + created.planId())).body(created);
    }

    @PutMapping("/{planId}/pricing")
    public TariffPlanDto reprice(@PathVariable String planId, @Valid @RequestBody UpdatePricingRequest request) {
        return service.updatePricing(planId, request);
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> retire(@PathVariable String planId) {
        service.retirePlan(planId);
        return ResponseEntity.noContent().build();
    }
}
