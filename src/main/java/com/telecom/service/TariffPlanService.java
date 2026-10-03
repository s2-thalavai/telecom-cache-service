package com.telecom.service;

import com.telecom.config.CacheNames;
import com.telecom.domain.SubscriberStatus;
import com.telecom.domain.TariffPlan;
import com.telecom.dto.CreateTariffPlanRequest;
import com.telecom.dto.DtoMapper;
import com.telecom.dto.TariffPlanDto;
import com.telecom.dto.UpdatePricingRequest;
import com.telecom.event.TariffPlanChangedEvent;
import com.telecom.exception.BusinessConflictException;
import com.telecom.exception.ResourceNotFoundException;
import com.telecom.repository.SubscriberRepository;
import com.telecom.repository.TariffPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tariff plan catalogue: read-heavy reference data -> long TTL, write-through on change.
 */
@Service
@Transactional(readOnly = true)
public class TariffPlanService {

    private static final Logger log = LoggerFactory.getLogger(TariffPlanService.class);
    private static final String CATALOG_KEY = "'ACTIVE'";

    private final TariffPlanRepository planRepository;
    private final SubscriberRepository subscriberRepository;
    private final ApplicationEventPublisher events;

    public TariffPlanService(TariffPlanRepository planRepository, SubscriberRepository subscriberRepository,
                             ApplicationEventPublisher events) {
        this.planRepository = planRepository;
        this.subscriberRepository = subscriberRepository;
        this.events = events;
    }

    /** sync = true: on launch day of a new plan, only ONE thread per pod loads it (stampede protection). */
    @Cacheable(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId", sync = true)
    public TariffPlanDto getPlan(String planId) {
        log.info("Cache MISS [{}] planId={} -> loading from catalogue DB", CacheNames.TARIFF_PLANS, planId);
        return planRepository.findById(planId)
                .map(DtoMapper::toPlanDto)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff plan " + planId + " not found"));
    }

    @Cacheable(cacheNames = CacheNames.PLAN_CATALOG, key = CATALOG_KEY)
    public List<TariffPlanDto> getActiveCatalog() {
        log.info("Cache MISS [{}] -> loading active catalogue from DB", CacheNames.PLAN_CATALOG);
        return loadActiveCatalog();
    }

    /** Proactive refresh: always executes and overwrites the cached catalogue. */
    @CachePut(cacheNames = CacheNames.PLAN_CATALOG, key = CATALOG_KEY)
    public List<TariffPlanDto> refreshActiveCatalog() {
        return loadActiveCatalog();
    }

    @Transactional
    @Caching(
            put = @CachePut(cacheNames = CacheNames.TARIFF_PLANS, key = "#result.planId()"),
            evict = @CacheEvict(cacheNames = CacheNames.PLAN_CATALOG, key = CATALOG_KEY))
    public TariffPlanDto createPlan(CreateTariffPlanRequest request) {
        if (planRepository.existsById(request.planId())) {
            throw new BusinessConflictException("Tariff plan " + request.planId() + " already exists");
        }
        TariffPlan plan = new TariffPlan(request.planId(), request.name(), request.planType(),
                request.monthlyFee(), request.dataGb(), request.validityDays());
        return DtoMapper.toPlanDto(planRepository.saveAndFlush(plan));
    }

    /**
     * Write-through: the new plan version is put into "tariff-plans", the catalogue list is evicted,
     * and a TariffPlanChangedEvent evicts dependent "subscriber-details" entries after commit.
     */
    @Transactional
    @Caching(
            put = @CachePut(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId"),
            evict = @CacheEvict(cacheNames = CacheNames.PLAN_CATALOG, key = CATALOG_KEY))
    public TariffPlanDto updatePricing(String planId, UpdatePricingRequest request) {
        TariffPlan plan = findEntity(planId);
        plan.setMonthlyFee(request.monthlyFee());
        plan.setDataGb(request.dataGb());
        plan.setValidityDays(request.validityDays());
        TariffPlan saved = planRepository.saveAndFlush(plan); // flush -> version incremented
        events.publishEvent(new TariffPlanChangedEvent(planId));
        log.info("Plan {} repriced to {} (version {})", planId, saved.getMonthlyFee(), saved.getVersion());
        return DtoMapper.toPlanDto(saved);
    }

    /** Retiring a plan removes it from every plan-related region. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId"),
            @CacheEvict(cacheNames = CacheNames.PLAN_CATALOG, key = CATALOG_KEY)})
    public void retirePlan(String planId) {
        TariffPlan plan = findEntity(planId);
        long onPlan = subscriberRepository.countByPlanIdAndStatusNot(planId, SubscriberStatus.TERMINATED);
        if (onPlan > 0) {
            throw new BusinessConflictException(
                    "Plan " + planId + " still has " + onPlan + " subscribers; migrate them first");
        }
        plan.setActive(false);
        events.publishEvent(new TariffPlanChangedEvent(planId));
    }

    private TariffPlan findEntity(String planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff plan " + planId + " not found"));
    }

    private List<TariffPlanDto> loadActiveCatalog() {
        return planRepository.findByActiveTrueOrderByMonthlyFeeAsc().stream()
                .map(DtoMapper::toPlanDto)
                .toList();
    }
}
