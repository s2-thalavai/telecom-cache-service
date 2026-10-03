package com.telecom.controller;

import com.telecom.dto.ChangePlanRequest;
import com.telecom.dto.RegisterSubscriberRequest;
import com.telecom.dto.SubscriberDetailsDto;
import com.telecom.dto.SubscriberDto;
import com.telecom.dto.UpdateStatusRequest;
import com.telecom.service.SubscriberService;
import com.telecom.util.Msisdn;
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

/**
 * Subscriber data is personal: cached on the server only, never by browsers/CDNs (no-store).
 * MSISDNs are normalised here, before they reach the cached service methods.
 */
@RestController
@RequestMapping("/api/v1/subscribers")
public class SubscriberController {

    private final SubscriberService service;

    public SubscriberController(SubscriberService service) {
        this.service = service;
    }

    @GetMapping("/{msisdn}")
    public ResponseEntity<SubscriberDto> get(@PathVariable String msisdn) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getSubscriber(Msisdn.normalize(msisdn)));
    }

    @GetMapping("/{msisdn}/details")
    public ResponseEntity<SubscriberDetailsDto> details(@PathVariable String msisdn) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.getSubscriberDetails(Msisdn.normalize(msisdn)));
    }

    @PostMapping
    public ResponseEntity<SubscriberDto> register(@Valid @RequestBody RegisterSubscriberRequest request) {
        String msisdn = Msisdn.normalize(request.msisdn());
        SubscriberDto created = service.register(msisdn, request);
        return ResponseEntity.created(URI.create("/api/v1/subscribers/" + msisdn)).body(created);
    }

    @PutMapping("/{msisdn}/plan")
    public SubscriberDto changePlan(@PathVariable String msisdn, @Valid @RequestBody ChangePlanRequest request) {
        return service.changePlan(Msisdn.normalize(msisdn), request.planId());
    }

    @PutMapping("/{msisdn}/status")
    public SubscriberDto updateStatus(@PathVariable String msisdn, @Valid @RequestBody UpdateStatusRequest request) {
        return service.updateStatus(Msisdn.normalize(msisdn), request.status(), request.reason());
    }

    @DeleteMapping("/{msisdn}")
    public ResponseEntity<Void> terminate(@PathVariable String msisdn) {
        service.terminate(Msisdn.normalize(msisdn));
        return ResponseEntity.noContent().build();
    }
}
