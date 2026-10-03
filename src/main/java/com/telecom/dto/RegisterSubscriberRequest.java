package com.telecom.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterSubscriberRequest(
        @NotBlank String msisdn,
        @NotBlank String name,
        @NotBlank String circle,
        @NotBlank String planId) {
}
