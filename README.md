# Telecom Cache Service (Spring REST + Spring Cache + Redis)

A complete Spring Boot 3.3 / Java 17 REST application for a telecom operator, with:

- **Subscribers** keyed by MSISDN.
- **Tariff plans** and the active plan catalogue.

It demonstrates Spring Cache with three interchangeable providers, selected purely by profile:

| Profile(s) | Cache provider | Use |
|---|---|---|
| *(default)* | Caffeine (in-memory, per JVM) + H2 | Local development, single instance |
| `redis` | Redis (shared, distributed) | Multiple pods, one consistent cache |
| `redis,two-level` | Custom manager: Caffeine L1 + Redis L2 + pub/sub invalidation | Lowest latency at scale |
| `redis,sentinel` / `redis,cluster` | Redis HA / horizontally scaled Redis | Production |
| `postgres` (add to any) | Shared PostgreSQL | Running several instances |

The answers to every interview question, mapped to this code, are in **[docs/CACHING_QA.md](docs/CACHING_QA.md)**.

## Project structure

```
telecom-cache-service/
├── pom.xml
├── docker-compose.yml                      # Redis (allkeys-lfu, 256 MB) + PostgreSQL
├── docs/CACHING_QA.md                      # Q&A for all caching concepts
└── src/
    ├── main/java/com/telecom/
    │   ├── TelecomCacheApplication.java    # @EnableCaching, @EnableScheduling
    │   ├── config/                         # cache configuration
    │   │   ├── CacheNames.java             # cache regions
    │   │   ├── TelecomCacheProperties.java # telecom.cache.* (TTL, size, namespace)
    │   │   ├── CaffeineCacheConfig.java    # default profile
    │   │   ├── RedisCacheConfig.java       # redis profile
    │   │   ├── CacheValueSerializers.java  # typed JSON serializers per region
    │   │   ├── TwoLevelCacheConfig.java    # two-level profile (+ pub/sub, metrics)
    │   │   ├── RedisHighAvailabilityConfig.java  # sentinel / cluster tuning
    │   │   └── CacheErrorHandlingConfig.java     # cache failures never break the API
    │   ├── cache/                          # caching strategies
    │   │   ├── TwoLevelCache.java          # L1 + L2 Cache implementation
    │   │   ├── TwoLevelCacheManager.java   # custom CacheManager
    │   │   ├── CacheInvalidationPublisher.java / Listener.java / Message.java
    │   │   ├── RelatedCacheInvalidator.java      # cross-region consistency after commit
    │   │   └── CatalogCacheRefresher.java        # warm-up + scheduled refresh
    │   ├── controller/                     # REST layer
    │   │   ├── TariffPlanController.java   # /api/v1/plans (HTTP caching: Cache-Control + ETag)
    │   │   ├── SubscriberController.java   # /api/v1/subscribers (no-store)
    │   │   └── CacheAdminController.java   # /api/v1/admin/caches (stats, evict, clear, refresh)
    │   ├── service/                        # business layer + cache annotations
    │   │   ├── TariffPlanService.java
    │   │   └── SubscriberService.java
    │   ├── repository/                     # persistence layer (Spring Data JPA)
    │   ├── domain/                         # JPA entities
    │   ├── dto/                            # immutable cached values + requests
    │   ├── event/TariffPlanChangedEvent.java
    │   ├── monitoring/CacheStatsService.java
    │   ├── exception/                      # ProblemDetail error handling
    │   └── util/Msisdn.java                # MSISDN normalisation + masking
    ├── main/resources/
    │   ├── application.yml                 # base + cache policy + actuator
    │   ├── application-redis.yml
    │   ├── application-sentinel.yml
    │   ├── application-cluster.yml
    │   ├── application-postgres.yml
    │   ├── data.sql / data-postgres.sql
    └── test/java/com/telecom/SubscriberCachingTest.java
```

## Run

**Default: Caffeine + H2, no infrastructure needed**

```bash
mvn spring-boot:run
```

**Redis as the shared cache**

```bash
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

**Two-level cache across two instances (shared PostgreSQL)**

Start the instances one after the other:

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=redis,two-level,postgres -Dspring-boot.run.arguments=--server.port=8080
mvn spring-boot:run -Dspring-boot.run.profiles=redis,two-level,postgres -Dspring-boot.run.arguments=--server.port=8081
```

**Tests**

```bash
mvn test
```

## Try it

**Plans: server cache plus HTTP cache headers**

```bash
curl -i localhost:8080/api/v1/plans
curl -i localhost:8080/api/v1/plans/POSTPAID_599
curl -i -H 'If-None-Match: "0"' localhost:8080/api/v1/plans/POSTPAID_599       # 304
```

**Subscribers: the first call logs "Cache MISS", the second doesn't**

```bash
curl localhost:8080/api/v1/subscribers/9876543210
curl localhost:8080/api/v1/subscribers/+91-98765-43210   # same key after normalisation
curl localhost:8080/api/v1/subscribers/9876543210/details
```

**@CachePut, @CacheEvict and related-cache eviction**

```bash
curl -X PUT localhost:8080/api/v1/subscribers/9876543210/plan -H 'Content-Type: application/json' -d '{"planId":"POSTPAID_999"}'
curl -X PUT localhost:8080/api/v1/subscribers/9876543210/status -H 'Content-Type: application/json' -d '{"status":"BARRED_OUTGOING","reason":"credit limit"}'
curl -X PUT localhost:8080/api/v1/plans/POSTPAID_599/pricing -H 'Content-Type: application/json' -d '{"monthlyFee":649,"dataGb":120,"validityDays":30}'
```

**Monitoring and management**

```bash
curl localhost:8080/api/v1/admin/caches
curl localhost:8080/actuator/caches
curl 'localhost:8080/actuator/metrics/cache.gets?tag=cache:subscribers&tag=result:hit'
curl localhost:8080/actuator/prometheus | grep cache_
curl -X DELETE localhost:8080/api/v1/admin/caches/subscribers/keys/9876543210
```

**Inspect Redis (namespaced keys and TTLs)**

```bash
docker exec -it telecom-redis redis-cli --scan --pattern 'telecom:v1:*'
docker exec -it telecom-redis redis-cli TTL 'telecom:v1:subscribers::9876543210'
```
