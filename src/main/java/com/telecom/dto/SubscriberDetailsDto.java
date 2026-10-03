package com.telecom.dto;

import java.io.Serializable;

/** Composite view (subscriber + plan) - a "related resource" cache entry. */
public record SubscriberDetailsDto(SubscriberDto subscriber, TariffPlanDto plan) implements Serializable {
}
