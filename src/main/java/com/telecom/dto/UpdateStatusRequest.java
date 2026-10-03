package com.telecom.dto;

import com.telecom.domain.SubscriberStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull SubscriberStatus status, String reason) {
}
