package com.telecom.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePlanRequest(@NotBlank String planId) {
}
