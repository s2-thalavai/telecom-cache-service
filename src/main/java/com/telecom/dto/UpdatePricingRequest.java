package com.telecom.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdatePricingRequest(
        @NotNull @DecimalMin("0.0") BigDecimal monthlyFee,
        @Min(0) int dataGb,
        @Min(1) int validityDays) {
}
