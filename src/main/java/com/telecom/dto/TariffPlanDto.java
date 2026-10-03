package com.telecom.dto;

import com.telecom.domain.PlanType;

import java.io.Serializable;
import java.math.BigDecimal;

/** Cached value: immutable DTO, never the JPA entity. */
public record TariffPlanDto(String planId, String name, PlanType planType, BigDecimal monthlyFee,
                            int dataGb, int validityDays, boolean active, long version) implements Serializable {
}
