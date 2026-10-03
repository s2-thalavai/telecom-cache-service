package com.telecom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "subscriber", indexes = @Index(name = "idx_subscriber_plan", columnList = "plan_id"))
public class Subscriber {

    @Id
    @Column(length = 10)
    private String msisdn; // normalised 10-digit Indian mobile number

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 40)
    private String circle; // telecom licence area, e.g. KARNATAKA

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriberStatus status;

    @Column(name = "plan_id", nullable = false, length = 40)
    private String planId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Subscriber() {
    }

    public Subscriber(String msisdn, String name, String circle, SubscriberStatus status,
                      String planId, Instant updatedAt) {
        this.msisdn = msisdn;
        this.name = name;
        this.circle = circle;
        this.status = status;
        this.planId = planId;
        this.updatedAt = updatedAt;
    }

    public String getMsisdn() { return msisdn; }
    public String getName() { return name; }
    public String getCircle() { return circle; }
    public SubscriberStatus getStatus() { return status; }
    public String getPlanId() { return planId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    public void setStatus(SubscriberStatus status) { this.status = status; }
    public void setPlanId(String planId) { this.planId = planId; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
