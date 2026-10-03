package com.telecom.service;

import com.telecom.config.CacheNames;
import com.telecom.domain.Subscriber;
import com.telecom.domain.SubscriberStatus;
import com.telecom.dto.DtoMapper;
import com.telecom.dto.RegisterSubscriberRequest;
import com.telecom.dto.SubscriberDetailsDto;
import com.telecom.dto.SubscriberDto;
import com.telecom.dto.TariffPlanDto;
import com.telecom.exception.BusinessConflictException;
import com.telecom.exception.InvalidRequestException;
import com.telecom.exception.ResourceNotFoundException;
import com.telecom.repository.SubscriberRepository;
import com.telecom.util.Msisdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Subscriber profiles: read on every app launch / IVR call / POS lookup, updated on plan change,
 * barring and termination. MSISDNs arriving here are already normalised by the controller.
 *
 * NOTE: prepaid balance and data usage are deliberately NOT cached - they come from the
 * charging system (OCS) in real time.
 */
@Service
@Transactional(readOnly = true)
public class SubscriberService {

    private static final Logger log = LoggerFactory.getLogger(SubscriberService.class);

    private final SubscriberRepository subscriberRepository;
    private final TariffPlanService tariffPlanService; // separate bean -> its cache is used

    public SubscriberService(SubscriberRepository subscriberRepository, TariffPlanService tariffPlanService) {
        this.subscriberRepository = subscriberRepository;
        this.tariffPlanService = tariffPlanService;
    }

    /** Terminated numbers are not cached (they will be recycled/reassigned later). */
    @Cacheable(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn",
            unless = "#result.status() == T(com.telecom.domain.SubscriberStatus).TERMINATED")
    public SubscriberDto getSubscriber(String msisdn) {
        log.info("Cache MISS [{}] msisdn={} -> loading from CRM DB", CacheNames.SUBSCRIBERS, Msisdn.mask(msisdn));
        return DtoMapper.toSubscriberDto(findEntity(msisdn));
    }

    /** Composite entry: depends on the subscriber AND on the tariff plan (see RelatedCacheInvalidator). */
    @Cacheable(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")
    public SubscriberDetailsDto getSubscriberDetails(String msisdn) {
        log.info("Cache MISS [{}] msisdn={}", CacheNames.SUBSCRIBER_DETAILS, Msisdn.mask(msisdn));
        Subscriber subscriber = findEntity(msisdn);
        TariffPlanDto plan = tariffPlanService.getPlan(subscriber.getPlanId());
        return new SubscriberDetailsDto(DtoMapper.toSubscriberDto(subscriber), plan);
    }

    /** New activation: write-through so the first self-care login is already a cache hit. */
    @Transactional
    @CachePut(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn")
    public SubscriberDto register(String msisdn, RegisterSubscriberRequest request) {
        if (subscriberRepository.existsById(msisdn)) {
            throw new BusinessConflictException("MSISDN " + Msisdn.mask(msisdn) + " is already allocated");
        }
        TariffPlanDto plan = requireActivePlan(request.planId());
        Subscriber subscriber = new Subscriber(msisdn, request.name(), request.circle(),
                SubscriberStatus.ACTIVE, plan.planId(), Instant.now());
        return DtoMapper.toSubscriberDto(subscriberRepository.saveAndFlush(subscriber));
    }

    /** Plan migration: update "subscribers" in place, drop the composite "subscriber-details". */
    @Transactional
    @Caching(
            put = @CachePut(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
            evict = @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn"))
    public SubscriberDto changePlan(String msisdn, String newPlanId) {
        Subscriber subscriber = findEntity(msisdn);
        if (subscriber.getStatus() != SubscriberStatus.ACTIVE) {
            throw new BusinessConflictException("Plan change allowed only for ACTIVE subscribers");
        }
        TariffPlanDto plan = requireActivePlan(newPlanId);
        subscriber.setPlanId(plan.planId());
        subscriber.setUpdatedAt(Instant.now());
        log.info("Subscriber {} migrated to plan {}", Msisdn.mask(msisdn), plan.planId());
        return DtoMapper.toSubscriberDto(subscriberRepository.saveAndFlush(subscriber));
    }

    /**
     * Frequently updated attribute (suspend / bar / unbar by fraud and collections systems):
     * EVICT instead of PUT - writes stay cheap, the next read reloads the truth.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
            @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")})
    public SubscriberDto updateStatus(String msisdn, SubscriberStatus newStatus, String reason) {
        if (newStatus == SubscriberStatus.TERMINATED) {
            throw new InvalidRequestException("Use DELETE /api/v1/subscribers/{msisdn} to terminate");
        }
        Subscriber subscriber = findEntity(msisdn);
        if (subscriber.getStatus() == SubscriberStatus.TERMINATED) {
            throw new BusinessConflictException("Subscriber is terminated");
        }
        subscriber.setStatus(newStatus);
        subscriber.setUpdatedAt(Instant.now());
        log.info("Subscriber {} status -> {} ({})", Msisdn.mask(msisdn), newStatus, reason);
        return DtoMapper.toSubscriberDto(subscriberRepository.saveAndFlush(subscriber));
    }

    /** Disconnection / port-out: remove the subscriber from every region. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
            @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")})
    public void terminate(String msisdn) {
        Subscriber subscriber = findEntity(msisdn);
        subscriber.setStatus(SubscriberStatus.TERMINATED);
        subscriber.setUpdatedAt(Instant.now());
        log.info("Subscriber {} terminated", Msisdn.mask(msisdn));
    }

    private TariffPlanDto requireActivePlan(String planId) {
        TariffPlanDto plan = tariffPlanService.getPlan(planId);
        if (!plan.active()) {
            throw new BusinessConflictException("Plan " + planId + " is not open for sale");
        }
        return plan;
    }

    private Subscriber findEntity(String msisdn) {
        return subscriberRepository.findById(msisdn)
                .orElseThrow(() -> new ResourceNotFoundException("Subscriber " + Msisdn.mask(msisdn) + " not found"));
    }
}
