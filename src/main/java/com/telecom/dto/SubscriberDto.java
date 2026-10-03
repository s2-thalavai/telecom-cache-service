package com.telecom.dto;

import com.telecom.domain.SubscriberStatus;

import java.io.Serializable;

public record SubscriberDto(String msisdn, String name, String circle, SubscriberStatus status,
                            String planId) implements Serializable {
}
