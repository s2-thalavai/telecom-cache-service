package com.telecom.dto;

import com.telecom.domain.PlanType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateTariffPlanRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{3,40}") String planId,
        @NotBlank String name,
        @NotNull PlanType planType,
        @NotNull @DecimalMin("0.0") BigDecimal monthlyFee,
        @Min(0) int dataGb,
        @Min(1) int validityDays) {
}
