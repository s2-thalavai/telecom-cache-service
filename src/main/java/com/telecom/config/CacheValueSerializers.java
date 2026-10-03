package com.telecom.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.telecom.dto.SubscriberDetailsDto;
import com.telecom.dto.SubscriberDto;
import com.telecom.dto.TariffPlanDto;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;
import java.util.Map;

/**
 * Typed JSON serializers per cache region.
 * - No JDK serialization (avoids deserialization attacks and brittle binary formats).
 * - No polymorphic default typing (records are final, so GenericJackson2JsonRedisSerializer
 *   would hand back LinkedHashMap instead of the DTO).
 */
final class CacheValueSerializers {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private CacheValueSerializers() {
    }

    static Map<String, RedisSerializer<?>> perCache() {
        JavaType planList = MAPPER.getTypeFactory().constructCollectionType(List.class, TariffPlanDto.class);
        return Map.of(
                CacheNames.TARIFF_PLANS, new Jackson2JsonRedisSerializer<>(MAPPER, TariffPlanDto.class),
                CacheNames.PLAN_CATALOG, new Jackson2JsonRedisSerializer<>(MAPPER, planList),
                CacheNames.SUBSCRIBERS, new Jackson2JsonRedisSerializer<>(MAPPER, SubscriberDto.class),
                CacheNames.SUBSCRIBER_DETAILS, new Jackson2JsonRedisSerializer<>(MAPPER, SubscriberDetailsDto.class));
    }
}
