package com.telecom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "tariff_plan")
public class TariffPlan {

    @Id
    @Column(name = "plan_id", length = 40)
    private String planId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 20)
    private PlanType planType;

    @Column(name = "monthly_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "data_gb", nullable = false)
    private int dataGb;

    @Column(name = "validity_days", nullable = false)
    private int validityDays;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version; // optimistic locking + used as HTTP ETag

    protected TariffPlan() {
    }

    public TariffPlan(String planId, String name, PlanType planType, BigDecimal monthlyFee,
                      int dataGb, int validityDays) {
        this.planId = planId;
        this.name = name;
        this.planType = planType;
        this.monthlyFee = monthlyFee;
        this.dataGb = dataGb;
        this.validityDays = validityDays;
    }

    public String getPlanId() { return planId; }
    public String getName() { return name; }
    public PlanType getPlanType() { return planType; }
    public BigDecimal getMonthlyFee() { return monthlyFee; }
    public int getDataGb() { return dataGb; }
    public int getValidityDays() { return validityDays; }
    public boolean isActive() { return active; }
    public Long getVersion() { return version; }

    public void setMonthlyFee(BigDecimal monthlyFee) { this.monthlyFee = monthlyFee; }
    public void setDataGb(int dataGb) { this.dataGb = dataGb; }
    public void setValidityDays(int validityDays) { this.validityDays = validityDays; }
    public void setActive(boolean active) { this.active = active; }
}
