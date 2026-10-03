# Caching Data in Spring REST with Spring Cache and Redis (Telecom Context)

Every answer refers to the code in this project (`telecom-cache-service`). File paths are relative to `src/main/java/com/telecom/` unless stated otherwise.

The domain has three parts:

- **Subscribers**, keyed by MSISDN (read on every app launch, IVR call and retail POS lookup).
- **Tariff plans** (reference data that rarely changes).
- **The plan catalogue** (read on every self-care home screen).

**What is deliberately not cached:** prepaid balance and data usage. Those are charging data and must come from the OCS in real time.

---

# Part A: Caching Data with Spring Cache or Redis

## A1. How can you configure and enable caching in a Spring REST application using Spring Cache? Provide an implementation.

There are four steps.

**Step 1. Add the dependencies** (`pom.xml`). Add `spring-boot-starter-cache`, plus a provider: `caffeine` for in-memory caching, or `spring-boot-starter-data-redis` for Redis.

**Step 2. Enable caching** (`TelecomCacheApplication.java`). This makes Spring wrap beans that use cache annotations in a proxy.

```java
@SpringBootApplication
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class TelecomCacheApplication { ... }
```

**Step 3. Declare a `CacheManager`** (`config/CaffeineCacheConfig.java`, the default profile). Without one, Spring Boot falls back to an unbounded `ConcurrentHashMap`, which is not acceptable for 200,000 subscriber profiles.

```java
@Bean
public CacheManager cacheManager(TelecomCacheProperties props) {
    return new TransactionAwareCacheManagerProxy(buildCaffeineCacheManager(props, null));
}
```

**Step 4. Annotate service methods** (`service/TariffPlanService.java`).

```java
@Cacheable(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId", sync = true)
public TariffPlanDto getPlan(String planId) {
    log.info("Cache MISS [{}] planId={} -> loading from catalogue DB", CacheNames.TARIFF_PLANS, planId);
    return planRepository.findById(planId).map(DtoMapper::toPlanDto)
            .orElseThrow(() -> new ResourceNotFoundException("Tariff plan " + planId + " not found"));
}
```

The cache policy lives in `application.yml` under `telecom.cache.caches.*`.

## A2. What are the advantages of using Spring Cache for caching data in Spring REST applications?

**Declarative.** Business code like `SubscriberService.changePlan` contains no `if (cache.get(...))` logic. The annotations describe the caching behaviour.

**Provider independent.** This project runs the same service code on Caffeine, Redis or a custom two-level cache just by switching profile (`redis`, `redis,two-level`).

**Expressive keys and conditions** via SpEL. For example, `unless = "#result.status() == T(...).TERMINATED"` skips caching terminated numbers that will later be recycled.

**Built-in robustness features:**

- `sync = true` for stampede protection.
- Transaction-aware caches, so the cache only changes after the DB commits.
- `CacheErrorHandler`, so a Redis outage degrades to DB reads (`config/CacheErrorHandlingConfig.java`).

**Observability for free.** Actuator `/actuator/caches` and Micrometer `cache.gets`/`cache.puts`/`cache.evictions` metrics.

**Testability.** `spring.cache.type=none` disables caching. Caching behaviour is unit-tested in `SubscriberCachingTest`.

## A3. Can you explain the concept of cache eviction and how it is handled in Spring Cache with an implementation?

Eviction means removing entries from the cache. There are three triggers.

**1. Explicit eviction (application driven)** with `@CacheEvict`. When a fraud system bars a number, both the profile and the composite view must go (`service/SubscriberService.java`):

```java
@Transactional
@Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")})
public SubscriberDto updateStatus(String msisdn, SubscriberStatus newStatus, String reason) { ... }
```

Useful attributes:

- `allEntries = true` clears the whole region.
- `beforeInvocation = true` evicts even if the method throws. The default is after successful completion, which is safer because a failed update leaves the cache untouched.

**2. Capacity-based eviction (provider driven).** Caffeine `maximumSize` uses the W-TinyLFU algorithm (`CaffeineCacheConfig`). Redis uses `maxmemory` with `maxmemory-policy allkeys-lfu` (`docker-compose.yml`).

**3. Time-based eviction (expiry).** Use Caffeine `expireAfterWrite` and Redis `entryTtl` (see A11 and B6).

## A4. How can you define cacheable methods using annotations like @Cacheable and @CachePut in Spring Cache? Demonstrate with sample code that uses these annotations.

**`@Cacheable`** looks up the key first. On a hit the method body is skipped. On a miss it runs and stores the result.

**`@CachePut`** always runs the method and stores the result. Use it for write-through on updates.

```java
// Read path
@Cacheable(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn",
        unless = "#result.status() == T(com.telecom.domain.SubscriberStatus).TERMINATED")
public SubscriberDto getSubscriber(String msisdn) { ... }

// New activation: first self-care login is already a hit
@Transactional
@CachePut(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn")
public SubscriberDto register(String msisdn, RegisterSubscriberRequest request) { ... }

// Plan migration: update one region, invalidate the dependent one
@Transactional
@Caching(
        put = @CachePut(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
        evict = @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn"))
public SubscriberDto changePlan(String msisdn, String newPlanId) { ... }
```

Rules to remember:

- Never put `@Cacheable` and `@CachePut` on the same method.
- The cached value of a `@CachePut` is the method's return value, so it must be the complete object.
- Cache DTOs (`dto/*Dto.java`), never JPA entities, because of lazy proxies and detached state.

## A5. What is the role of the cache manager in Spring Cache, and how can you customize its behavior? Provide a sample implementation.

`CacheManager` is the SPI between the annotations and the cache store. Given a cache name (for example `"subscribers"`), it returns a `Cache` object. Every policy decision lives in the manager:

- Which regions exist.
- TTL and size per region.
- Serialization.
- Key prefixes.
- Null handling.
- Transaction awareness.
- Statistics.

Customisations in this project (`config/CaffeineCacheConfig.java`):

```java
public static CaffeineCacheManager buildCaffeineCacheManager(TelecomCacheProperties props, Duration ttlCap) {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setAllowNullValues(false);
    manager.setCacheNames(List.of()); // static mode: no accidental unbounded caches
    for (String name : CacheNames.ALL) {
        Duration ttl = props.ttlFor(name);
        if (ttlCap != null && ttlCap.compareTo(ttl) < 0) ttl = ttlCap;
        manager.registerCustomCache(name, Caffeine.newBuilder()
                .maximumSize(props.maxSizeFor(name))
                .expireAfterWrite(ttl)
                .recordStats()
                .build());
    }
    return manager;
}
```

The Redis equivalent (`config/RedisCacheConfig.java`) customises per-region TTL, typed JSON serializers, the `telecom:v1:` key namespace, the SCAN-based `clear()`, `disableCreateOnMissingCache()`, `transactionAware()` and `enableStatistics()`.

The fully custom manager is `cache/TwoLevelCacheManager.java` (see B12). If you rely on Boot's auto-configured manager instead, use a `CacheManagerCustomizer<CaffeineCacheManager>` or `RedisCacheManagerBuilderCustomizer` bean to tweak it.

## A6. Can you provide an example of caching data in a Spring REST application using Spring Cache annotations?

End-to-end flow for `GET /api/v1/subscribers/{msisdn}`:

```text
SubscriberController.get  ->  Msisdn.normalize("+91-98765-43210") = "9876543210"
                          ->  SubscriberService.getSubscriber("9876543210")   [@Cacheable proxy]
                                 hit  -> return cached SubscriberDto
                                 miss -> SubscriberRepository.findById -> DTO -> stored in "subscribers"
```

**Controller** (`controller/SubscriberController.java`):

```java
@GetMapping("/{msisdn}")
public ResponseEntity<SubscriberDto> get(@PathVariable String msisdn) {
    return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())      // personal data: never cached by browsers/CDNs
            .body(service.getSubscriber(Msisdn.normalize(msisdn)));
}
```

**Demonstration:**

```bash
curl localhost:8080/api/v1/subscribers/9876543210       # log: Cache MISS [subscribers] msisdn=98XXXXX210
curl localhost:8080/api/v1/subscribers/9876543210       # no log -> served from cache
curl localhost:8080/api/v1/subscribers/+919876543210    # same normalised key -> hit
```

`SubscriberCachingTest.secondLookupIsServedFromCache` proves it: two calls, but `findById` is invoked once.

## A7. What are the considerations for cache consistency and cache synchronization when using Spring Cache in a distributed environment? Explain with appropriate codes.

Spring Cache itself does **not** synchronise caches across JVMs. The considerations and how this project handles each one:

| Problem | Handling in this project |
|---|---|
| Each pod's local cache diverges | Use Redis (`redis` profile) as a single shared copy, or two-level with pub/sub invalidation (`two-level` profile) |
| Cache updated but DB transaction rolls back | Transaction-aware caches: `TransactionAwareCacheManagerProxy`, `RedisCacheManager...transactionAware()`, `TwoLevelCacheManager.setTransactionAware(true)` |
| Another cache region holds a copy of the changed data | `RelatedCacheInvalidator` evicts dependent `subscriber-details` entries after commit |
| Stampede when a hot key expires | `@Cacheable(sync = true)` on `getPlan` (one loader per key per JVM) |
| Pub/sub message lost | Short L1 TTL (`telecom.cache.l1-ttl: 30s`) bounds staleness |
| Concurrent updates to the same row | JPA `@Version` (optimistic locking). A conflict returns 409 and no cache write happens |

**Write path in the two-level cache** (`cache/TwoLevelCache.java`):

```java
@Override
public void put(Object key, Object value) {
    remote.put(key, value);                     // L2 (Redis) - the shared truth
    local.put(localKey(key), value);            // L1 of this pod
    publisher.keyChanged(name, localKey(key));  // other pods drop their L1 copy
}
```

**Every other pod** (`cache/CacheInvalidationListener.java`):

```java
if (instanceId.equals(msg.origin())) return;   // ignore own messages
if (msg.clearAll()) cacheManager.clearLocal(msg.cacheName());
else cacheManager.evictLocal(msg.cacheName(), msg.key());
```

Because the manager is transaction-aware, `put`/`evict` (and therefore the broadcast) run only after a successful commit.

## A8. How can you integrate Redis as a caching solution in a Spring REST application?

1. **Add dependencies:** `spring-boot-starter-data-redis` (Lettuce client) and `commons-pool2`.
2. **Configure the connection** in `src/main/resources/application-redis.yml` (host, port, credentials, TLS, timeouts, pool).
3. **Provide a `RedisCacheManager`** in `config/RedisCacheConfig.java`, activated by the `redis` profile.
4. **Keep the service code unchanged.** The same `@Cacheable` annotations now store `telecom:v1:subscribers::9876543210` in Redis.
5. **Add `LoggingCacheErrorHandler`** (`config/CacheErrorHandlingConfig.java`) so a Redis outage never turns into HTTP 500s.

Run it with:

```bash
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

## A9. What are the benefits of using Redis as a caching provider compared to in-memory caching?

| | In-memory (Caffeine) | Redis |
|---|---|---|
| Shared across pods | No: each pod has its own copy and they can diverge | Yes: one copy, consistent for all pods |
| Survives restart/deploy | No: cold cache after every rollout | Yes: pods restart warm |
| Capacity | Limited by JVM heap, adds GC pressure | Separate memory, scales independently (Cluster) |
| Invalidation | Must reach every pod | One `DEL` and every pod sees it |
| Latency | Fastest (~µs, no network) | ~0.3–1 ms network round trip and serialization |
| Extra features | – | TTL per key, pub/sub, atomic ops, HA (Sentinel), sharding (Cluster) |
| Operational cost | None | Another system to run, secure and monitor |

In telecom, a 20-pod self-care API with in-memory caches can serve 20 different versions of a subscriber's plan for up to the TTL. Redis removes that inconsistency, and the two-level option keeps microsecond reads for the hottest keys.

## A10. How do you configure Spring to use Redis as a caching provider in a Spring REST application? Give the implementation demonstrating this configuration.

**Implementation** (`config/RedisCacheConfig.java`):

```java
@Configuration
@Profile("redis & !two-level")
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, TelecomCacheProperties props) {
        return buildRedisCacheManager(connectionFactory, props, true);
    }

    public static RedisCacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory,
                                                           TelecomCacheProperties props,
                                                           boolean transactionAware) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(props.defaultTtl())
                .disableCachingNullValues()
                .computePrefixWith(props::keyPrefix)                      // telecom:v1:<region>::
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        CacheValueSerializers.perCache().forEach((name, serializer) ->
                perCache.put(name, defaults
                        .entryTtl(props.ttlFor(name))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))));

        BatchStrategy clearStrategy = props.useScanForClear() ? BatchStrategies.scan(500) : BatchStrategies.keys();
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, clearStrategy);

        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(writer)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .disableCreateOnMissingCache()
                .enableStatistics();
        if (transactionAware) builder.transactionAware();
        return builder.build();
    }
}
```

**Why typed serializers** (`config/CacheValueSerializers.java`):

- JDK serialization is unsafe and brittle.
- `GenericJackson2JsonRedisSerializer` doesn't write type info for final classes like Java records, so values come back as `LinkedHashMap` and cause a `ClassCastException`.
- A `Jackson2JsonRedisSerializer` per region knows its exact type, including `List<TariffPlanDto>` for the catalogue.

## A11. Can you explain the concept of cache expiration and how it is handled in Redis caching with sample code?

Expiration means an entry becomes invalid after a period, regardless of whether anyone changed the data. It is the safety net that bounds staleness when an invalidation is missed (for example, a change made directly in the CRM database).

In Redis, `RedisCacheWriter` stores entries with `SET key value PX <ttl>`, and Redis deletes the key when it expires, both lazily on access and through active expiry sampling. Spring computes the TTL from the region's `RedisCacheConfiguration`.

**Per-region TTL from configuration** (`application.yml` → `TelecomCacheProperties.ttlFor` → `RedisCacheConfig`):

```yaml
telecom:
  cache:
    default-ttl: 10m
    caches:
      tariff-plans:       { ttl: 6h,  max-size: 1000 }
      plan-catalog:       { ttl: 15m, max-size: 10 }
      subscribers:        { ttl: 5m,  max-size: 200000 }
      subscriber-details: { ttl: 2m,  max-size: 100000 }
```

**Dynamic TTL per entry** (Spring Data Redis 3.2+). This is an optional extension: suspended or barred subscribers change more often, so give them a shorter TTL and add jitter to avoid mass expiry.

```java
RedisCacheConfiguration subscribers = defaults.entryTtl((key, value) -> {
    Duration base = value instanceof SubscriberDto s && s.status() == SubscriberStatus.ACTIVE
            ? Duration.ofMinutes(5) : Duration.ofSeconds(30);
    return base.plusSeconds(ThreadLocalRandom.current().nextInt(0, 30)); // jitter
});
```

**Idle-based expiry.** `RedisCacheConfiguration.enableTimeToIdle()` (Spring Data Redis 3.2+, needs Redis 6.2+ for `GETEX`) resets the TTL on every read, which suits bursty roaming-rate lookups.

**Verify expiry:**

```bash
docker exec -it telecom-redis redis-cli TTL 'telecom:v1:subscribers::9876543210'   # e.g. 287 (seconds left)
```

## A12. What are the potential challenges or limitations of using Redis as a caching solution in a Spring REST application?

**Network and serialization cost.** Every hit costs a round trip plus JSON (de)serialization. This is why the `two-level` profile adds a local L1.

**Availability dependency.** If Redis is slow, every request is slow. Mitigations in this project:

- Short timeouts (`timeout: 250ms`).
- A bounded Lettuce pool.
- `LoggingCacheErrorHandler`, which falls through to the DB.
- Sentinel or Cluster for HA (B10).

**Stampede after Redis failover or flush.** All keys are missing at once and the CRM DB gets flooded. Use `sync = true`, warm-up (`CatalogCacheRefresher`) and jittered TTLs.

**`clear()` uses `KEYS` by default.** This blocks Redis on large keyspaces. The project switches to `BatchStrategies.scan(500)`.

**Memory limits and eviction policy.** Without `maxmemory` and a policy, Redis can run out of memory or refuse writes. Watch big values: never cache a full 50,000-row usage list.

**Serialization compatibility.** A changed DTO cannot read entries written by the previous release. Bump `telecom.cache.schema-version` (`v1` to `v2`) for a clean namespace.

**No transactional coupling with the database.** The cache and DB can still diverge on crashes between commit and cache write. Keep TTLs as the safety net.

**Pub/sub is at-most-once.** A disconnected pod misses invalidations, so L1 TTLs must stay short.

**Cluster specifics.**

- Multi-key operations need hash tags.
- `SCAN` works per node.
- Pub/sub messages are broadcast to all nodes.

**Hot keys.** Redis is single-threaded per shard. The plan catalogue read by millions sits on one shard, so serve it from L1 or HTTP caching.

## A13. How can you monitor and manage the cache in a Spring REST application using Redis as a caching provider? Demonstrate with a code.

**1. Statistics enabled on the manager:** `RedisCacheManager.builder(...).enableStatistics()`. Spring Boot then binds Micrometer metrics:

- `cache.gets{result=hit|miss}`
- `cache.puts`
- `cache.removals`

**2. Provider-independent stats service** (`monitoring/CacheStatsService.java`):

```java
if (target instanceof RedisCache redis) {
    CacheStatistics s = redis.getStatistics();
    return new CacheStatsView(cacheName, "redis", s.getHits(), s.getMisses(),
            ratio(s.getHits(), s.getMisses()), null,
            Map.of("puts", s.getPuts(), "deletes", s.getDeletes()));
}
```

**3. Management endpoints** (`controller/CacheAdminController.java`):

```bash
curl localhost:8080/api/v1/admin/caches                                     # hit ratio per region
curl -X DELETE localhost:8080/api/v1/admin/caches/subscribers/keys/9876543210  # evict one subscriber
curl -X DELETE localhost:8080/api/v1/admin/caches/plan-catalog              # clear a region (SCAN-based)
curl -X POST   localhost:8080/api/v1/admin/caches/plan-catalog/refresh      # force refresh
curl localhost:8080/actuator/caches                                         # Spring Boot actuator view
```

**4. Redis-side tools:**

```bash
redis-cli INFO stats | grep keyspace          # keyspace_hits / keyspace_misses
redis-cli INFO memory | grep used_memory_human
redis-cli --bigkeys                           # oversized values
redis-cli MEMORY USAGE 'telecom:v1:plan-catalog::ACTIVE'
redis-cli SLOWLOG GET 10
redis-cli --latency
```

**5. Prometheus and Grafana:** see B9 and B15 for queries and alerts.

## A14. Are there any security considerations or best practices when using Redis as a caching solution in a Spring REST application?

**1. Authenticate with least privilege (Redis 6+ ACL).** Give the app a user restricted to its namespace and channel:

```text
ACL SETUSER telecom-api on >${REDIS_PASSWORD} ~telecom:* &telecom:cache:* +@read +@write +@pubsub -@dangerous
```

**2. Encrypt in transit.** Set `spring.data.redis.ssl.enabled=true` (`application-redis.yml`). Subscriber data is personal data under India's DPDP Act and TRAI/DoT licence conditions, and GDPR for EU roamers.

**3. Network isolation.** Use a private subnet or VPC only, never a public IP. Keep `protected-mode yes`.

**4. Disable dangerous commands.** Disable or rename `FLUSHALL`, `FLUSHDB`, `KEYS`, `CONFIG` and `DEBUG` for application users.

**5. Safe serialization.** Use typed Jackson serializers, no JDK serialization and no permissive polymorphic typing (`CacheValueSerializers`). This prevents deserialization gadget attacks if someone can write to Redis.

**6. Validate and normalise keys.** `Msisdn.normalize` runs before caching, which prevents cache poisoning and key injection.

**7. Minimise and protect personal data:**

- Cache only what screens need. Never cache OTPs, tokens or KYC documents in shared regions.
- Keep TTLs short for PII.
- Evict on termination or port-out (`SubscriberService.terminate`).
- Mask MSISDNs in logs (`Msisdn.mask`).
- Consider hashing MSISDNs in keys if Redis access is broad.

**8. Keep HTTP caches away from personal data.** Send `Cache-Control: no-store` on subscriber endpoints and `public` only on the plan catalogue.

**9. Authorization before the cache.** A cache hit skips the method body, so access checks belong in the controller or security layer, not inside `@Cacheable` methods.

**10. Protect management endpoints.** Restrict `/api/v1/admin/caches/**` and `/actuator/**` to an OPS role or the internal network. A cache flush is effectively a DoS against downstream systems.

**11. Store secrets in Vault or Key Vault** and inject them as environment variables (`REDIS_PASSWORD`), never in Git.

---

# Part B: More About Caching with Spring Cache or Redis

## B1. How would you configure and enable Spring Cache in a Spring REST application to cache the results of a specific method?

Enable caching once with `@EnableCaching` and a `CacheManager` (A1), then annotate only the method you want cached. Everything else stays uncached.

```java
// TariffPlanService - ONLY this method is cached
@Cacheable(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId", sync = true)
public TariffPlanDto getPlan(String planId) { ... }
```

Points to remember for a single method:

- **Visibility.** It must be `public` and called from another bean through the proxy. `SubscriberService` calls `tariffPlanService.getPlan(...)`, which is cached. A `this.getPlan(...)` call inside `TariffPlanService` would not be.
- **Explicit keys** (`key = "#planId"`) are clearer than the default `SimpleKey` built from all parameters.
- **Regions with their own policy.** The region `tariff-plans` gets its own TTL of 6h and size of 1000 from `application.yml`.

## B2. Can you demonstrate the usage of the @Cacheable annotation in a Spring REST application to cache the response of an API endpoint?

`GET /api/v1/plans` returns the active catalogue, the first call of every self-care app session. The data is cached in the service and the endpoint also sends HTTP caching headers.

```java
// service/TariffPlanService.java
@Cacheable(cacheNames = CacheNames.PLAN_CATALOG, key = "'ACTIVE'")
public List<TariffPlanDto> getActiveCatalog() {
    log.info("Cache MISS [{}] -> loading active catalogue from DB", CacheNames.PLAN_CATALOG);
    return loadActiveCatalog();
}

// controller/TariffPlanController.java
@GetMapping
public ResponseEntity<List<TariffPlanDto>> catalog() {
    List<TariffPlanDto> plans = service.getActiveCatalog();
    return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .eTag("\"catalog-" + Integer.toHexString(plans.hashCode()) + "\"")
            .body(plans);
}
```

**Why annotate the service and not the controller?** A controller returning `ResponseEntity` would cache headers and status along with the body. Caching the DTO lets other callers (`SubscriberService`, schedulers) reuse it.

**Single-plan endpoint.** `GET /api/v1/plans/{planId}` additionally uses the entity `@Version` as ETag. Spring answers `304 Not Modified` automatically for `If-None-Match: "<version>"`.

## B3. How would you configure Redis as the caching provider in a Spring REST application and integrate it with Spring Cache?

1. **Add dependencies** (`pom.xml`): `spring-boot-starter-data-redis`, `commons-pool2`.
2. **Configure the connection** in `application-redis.yml`:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      ssl: { enabled: ${REDIS_SSL:false} }
      timeout: 250ms
      lettuce:
        pool: { enabled: true, max-active: 32, max-idle: 16, min-idle: 4, max-wait: 200ms }
```

3. **Provide the `RedisCacheManager` bean** (A10). Because it is the `CacheManager` in the context, every `@Cacheable`/`@CachePut`/`@CacheEvict` uses Redis.
4. **Run and verify:**

```bash
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=redis
curl localhost:8080/api/v1/subscribers/9876543210
docker exec -it telecom-redis redis-cli --scan --pattern 'telecom:v1:*'
#  telecom:v1:plan-catalog::ACTIVE
#  telecom:v1:subscribers::9876543210
docker exec -it telecom-redis redis-cli GET 'telecom:v1:subscribers::9876543210'
#  {"msisdn":"9876543210","name":"Arun Kumar","circle":"KARNATAKA","status":"ACTIVE","planId":"POSTPAID_599"}
```

## B4. Can you provide an example of using the @CachePut annotation in a Spring REST application to update the cached data?

**Example 1: plan migration** (`PUT /api/v1/subscribers/{msisdn}/plan`). The updated subscriber replaces the cached one, so the next read is a hit with fresh data:

```java
@Transactional
@Caching(
        put = @CachePut(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
        evict = @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn"))
public SubscriberDto changePlan(String msisdn, String newPlanId) {
    Subscriber subscriber = findEntity(msisdn);
    ...
    subscriber.setPlanId(plan.planId());
    return DtoMapper.toSubscriberDto(subscriberRepository.saveAndFlush(subscriber));
}
```

**Example 2: repricing** (`PUT /api/v1/plans/{planId}/pricing`). The new plan version is put into `tariff-plans`, using `@CachePut(cacheNames = TARIFF_PLANS, key = "#planId")` in `updatePricing`.

**Example 3: scheduled refresh** (`refreshActiveCatalog`). `@CachePut` overwrites the catalogue without ever leaving the cache empty.

**Proof:** `SubscriberCachingTest.cachePutKeepsCacheInSyncAfterPlanChange`. After `changePlan`, the next `getSubscriber` returns `POSTPAID_999` and the repository is **not** called again.

## B5. How would you handle cache eviction in a Spring REST application using Spring Cache, specifically for a frequently updated resource?

Subscriber **status** changes frequently: collections suspend, fraud bars outgoing calls, payment unbars, sometimes several times a day. The strategy:

**1. Evict instead of put.** A write only deletes the key, which is cheap. The next read loads the truth once. Re-putting on every change wastes work if nobody reads in between.

```java
@Transactional
@Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")})
public SubscriberDto updateStatus(String msisdn, SubscriberStatus newStatus, String reason) { ... }
```

**2. Evict after commit, not before.** The default `beforeInvocation = false` plus the transaction-aware manager means a failed update doesn't evict, and a concurrent reader can't re-cache the old value mid-transaction.

**3. Short TTL as a backstop** for changes made outside the API (for example, a bulk barring job in the CRM): `subscribers: 5m`, `subscriber-details: 2m`.

**4. Separate stable from volatile data.** Name and circle rarely change, while status changes often and balance changes constantly. Balance is never cached. If status churn becomes very high, cache the profile without status and read status live.

**5. For changes from other systems,** consume their events (Kafka or CDC) and evict there. See B13.

## B6. Can you explain how to configure cache expiration policies for Redis caching in a Spring REST application?

Three levels, from coarse to fine:

**1. Default TTL** for any region: `RedisCacheConfiguration.defaultCacheConfig().entryTtl(props.defaultTtl())`, configured with `telecom.cache.default-ttl: 10m`.

**2. Per-region TTL** from configuration, so changing it doesn't need a code change:

```java
CacheValueSerializers.perCache().forEach((name, serializer) ->
        perCache.put(name, defaults.entryTtl(props.ttlFor(name)) ... ));
```

```yaml
telecom.cache.caches:
  tariff-plans: { ttl: 6h }       # reference data
  subscribers:  { ttl: 5m }       # changes on plan change / barring
  subscriber-details: { ttl: 2m } # composite, depends on two sources
```

**3. Per-entry TTL** with a `TtlFunction` (A11), for example a shorter TTL for non-active subscribers plus jitter.

Choosing TTLs:

- **Staleness tolerance:** how wrong may this be, and for how long?
- **Change frequency.**
- **Cost of a miss:** a CRM SOAP call versus a local DB read.
- **Memory.**

Combine TTLs with explicit eviction. The TTL is the safety net, not the primary invalidation mechanism.

Also set the server-side memory policy (`--maxmemory 256mb --maxmemory-policy allkeys-lfu` in `docker-compose.yml`). When memory fills up, the least-frequently-used keys are evicted before their TTL.

## B7. How would you implement cache synchronization across multiple instances of a Spring REST application when using Redis as the caching provider? Explain with a demonstration.

**Option 1: Redis as the only cache** (`redis` profile). There is nothing to synchronise: every instance reads and writes the same key, and a `@CacheEvict` on pod A is immediately visible to pod B. This is the simplest and recommended default.

**Option 2: Two-level cache** (`redis,two-level` profiles). Each pod has a Caffeine L1 for microsecond reads, so L1s must be synchronised. This project does it with Redis pub/sub:

```text
Pod A: PUT /subscribers/9876543210/plan
  -> DB commit
  -> TwoLevelCache.put: Redis SET (L2) + local L1 put
  -> PUBLISH telecom:cache:invalidation {"origin":"A","cacheName":"subscribers","key":"9876543210"}
Pod B: CacheInvalidationListener -> evictLocal("subscribers","9876543210")
  -> next read on B: L1 miss -> L2 hit (new plan) -> L1 refilled
```

The code is in `cache/TwoLevelCache.java`, `cache/CacheInvalidationPublisher.java`, `cache/CacheInvalidationListener.java` and `config/TwoLevelCacheConfig.java` (listener container on channel `telecom:cache:invalidation`).

**Demonstration** (two instances sharing PostgreSQL and Redis):

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=redis,two-level,postgres -Dspring-boot.run.arguments=--server.port=8080
mvn spring-boot:run -Dspring-boot.run.profiles=redis,two-level,postgres -Dspring-boot.run.arguments=--server.port=8081

curl localhost:8080/api/v1/subscribers/9876543210   # A: miss -> DB, fills L2 + L1(A)
curl localhost:8081/api/v1/subscribers/9876543210   # B: L2 hit, fills L1(B)
curl localhost:8081/api/v1/subscribers/9876543210   # B: L1 hit

curl -X PUT localhost:8080/api/v1/subscribers/9876543210/plan \
     -H 'Content-Type: application/json' -d '{"planId":"POSTPAID_999"}'   # A updates + broadcasts
curl localhost:8081/api/v1/subscribers/9876543210   # B: L1 was evicted -> L2 -> POSTPAID_999

curl localhost:8081/api/v1/admin/caches/subscribers # l1Hits / l2Hits / misses for pod B
docker exec -it telecom-redis redis-cli SUBSCRIBE telecom:cache:invalidation   # watch the broadcasts
```

**Limitations:** pub/sub is fire-and-forget, so the L1 TTL (30s) bounds staleness if a message is lost. For guaranteed delivery, use a Kafka topic or Redis Streams instead.

## B8. Can you demonstrate the usage of the @CacheEvict annotation in a Spring REST application to remove specific items from the cache?

**1. Single key, multiple regions: disconnection or port-out**

`DELETE /api/v1/subscribers/{msisdn}`:

```java
@Transactional
@Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, key = "#msisdn"),
        @CacheEvict(cacheNames = CacheNames.SUBSCRIBER_DETAILS, key = "#msisdn")})
public void terminate(String msisdn) { ... }
```

**2. Fixed key: retiring a plan removes it and the catalogue list**

`DELETE /api/v1/plans/{planId}`:

```java
@Transactional
@Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.TARIFF_PLANS, key = "#planId"),
        @CacheEvict(cacheNames = CacheNames.PLAN_CATALOG, key = "'ACTIVE'")})
public void retirePlan(String planId) { ... }
```

**3. Whole region (`allEntries = true`), for example after a bulk CRM migration:**

```java
@CacheEvict(cacheNames = CacheNames.SUBSCRIBERS, allEntries = true)
public void onBulkCrmSync() { }
```

**4. Programmatic eviction** when the key set is computed at runtime: `RelatedCacheInvalidator` evicts all `subscriber-details` for a changed plan. `CacheAdminController` evicts one key on demand.

```bash
curl -X DELETE localhost:8080/api/v1/subscribers/9445566778
curl -X DELETE localhost:8080/api/v1/admin/caches/subscribers/keys/9876543210
```

## B9. How would you measure the effectiveness of caching in a Spring REST application using Spring Cache or Redis? Explain with a sample implementation.

Measure three things:

1. **Hit ratio per region**, the primary KPI. Targets: catalogue and plans above 95%, subscribers above 80%.
2. **Latency improvement**: p95 of `http_server_requests_seconds` with and without cache.
3. **Downstream load reduction**: DB queries, CRM calls and connection pool usage.

**Sample implementation:**

**a) Statistics** come from Caffeine (`recordStats()`) and Redis (`enableStatistics()`). Spring Boot exports them as Micrometer meters.

**b) `CacheStatsService` and `GET /api/v1/admin/caches`** give a provider-independent JSON view:

```json
[{"cacheName":"subscribers","provider":"redis","hits":1840,"misses":212,"hitRatio":0.8967,
  "estimatedSize":null,"details":{"puts":230,"deletes":14}}]
```

**c) Prometheus queries:**

```text
# hit ratio per region
sum by (cache) (rate(cache_gets_total{result="hit"}[5m]))
  / sum by (cache) (rate(cache_gets_total[5m]))

# two-level breakdown
sum by (cache, result) (rate(telecom_cache_two_level_gets_total[5m]))

# effect on latency
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri="/api/v1/subscribers/{msisdn}"}[5m])))
```

To get the latency histogram, set `management.metrics.distribution.percentiles-histogram.http.server.requests=true`.

**d) Automated proof in tests** (`SubscriberCachingTest`): verify that the repository is called once for two reads.

**e) Load test** with Gatling or k6: 1,000 RPS over 10,000 MSISDNs. Compare DB QPS with the cache on and off (`spring.cache.type=none` for a baseline run).

## B10. Can you explain the process of integrating Redis Sentinel or Redis Cluster with a Spring REST application for high availability caching with sample implementation?

**Redis Sentinel** provides HA for a single dataset: one master, replicas, and three or more sentinels that elect a new master on failure. Spring Boot's Lettuce client asks the sentinels for the current master and reconnects automatically after failover.

```yaml
# src/main/resources/application-sentinel.yml   (run with profiles: redis,sentinel)
spring:
  data:
    redis:
      sentinel:
        master: ${REDIS_SENTINEL_MASTER:telecom-master}
        nodes: ${REDIS_SENTINELS:sentinel-1:26379,sentinel-2:26379,sentinel-3:26379}
        password: ${REDIS_SENTINEL_PASSWORD:}
```

**Redis Cluster** provides HA plus sharding: data is split across 16,384 hash slots over several masters, each with replicas. Use it when the subscriber cache outgrows one node's memory or throughput.

```yaml
# src/main/resources/application-cluster.yml   (run with profiles: redis,cluster)
spring:
  data:
    redis:
      cluster:
        nodes: ${REDIS_CLUSTER_NODES:redis-node-1:6379,redis-node-2:6379,redis-node-3:6379}
        max-redirects: 3
      lettuce:
        cluster:
          refresh: { period: 30s, adaptive: true }   # follow slot migrations and failovers
```

**Client tuning** (`config/RedisHighAvailabilityConfig.java`):

```java
@Bean
public LettuceClientConfigurationBuilderCustomizer telecomReadFromCustomizer(
        @Value("${telecom.cache.read-from:upstream}") String readFrom) {
    return builder -> builder.readFrom(ReadFrom.valueOf(readFrom));
}
```

`upstream` (master) gives read-your-writes. `replicaPreferred` offloads reads, but right after a `@CachePut` a replica may still return the old value.

**What happens during failover:**

- Commands time out for a few seconds.
- `LoggingCacheErrorHandler` turns cache errors into misses, so requests fall through to the DB.
- The API stays up, just slower.

**Cluster specifics:**

- `clear()` uses KEYS fanned out to all masters (`use-scan-for-clear: false`).
- Pub/sub messages reach all nodes, so the two-level invalidation still works.
- Multi-key operations must share a hash slot via hash tags, e.g. `{9876543210}`.

**Managed options:** Azure Cache for Redis, AWS ElastiCache and GCP Memorystore provide Sentinel-like HA or cluster mode. Point the same properties at their endpoints with TLS enabled.

## B11. How would you handle cache consistency when updating or deleting related resources in a Spring REST application with Redis caching? Explain with a demonstration.

**The problem.** `subscriber-details` caches a subscriber together with a copy of their tariff plan. Repricing `POSTPAID_599` makes every cached details entry for subscribers on that plan stale, and no annotation on `updatePricing` knows those MSISDNs.

**The solution in this project:**

1. `TariffPlanService.updatePricing` updates its own regions declaratively (`@CachePut` on `tariff-plans`, `@CacheEvict` on `plan-catalog`) and publishes a domain event:

```java
events.publishEvent(new TariffPlanChangedEvent(planId));
```

2. `cache/RelatedCacheInvalidator.java` reacts **only after the transaction commits**, looks up the affected subscribers and evicts their composite entries:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
public void onTariffPlanChanged(TariffPlanChangedEvent event) {
    Cache details = cacheManager.getCache(CacheNames.SUBSCRIBER_DETAILS);
    List<String> msisdns = subscriberRepository.findMsisdnsByPlanId(event.planId());
    msisdns.forEach(details::evict);
}
```

3. **Deletes are guarded.** `retirePlan` refuses (409) while non-terminated subscribers are still on the plan, so no subscriber can point at a removed plan.

4. **Subscriber-side changes** (`changePlan`, `updateStatus`, `terminate`) evict their own `subscriber-details` entry in the same annotation set.

**Demonstration:**

```bash
curl localhost:8080/api/v1/subscribers/9123456780/details        # plan.monthlyFee = 599.00 (cached)
curl -X PUT localhost:8080/api/v1/plans/POSTPAID_599/pricing \
     -H 'Content-Type: application/json' -d '{"monthlyFee":649,"dataGb":120,"validityDays":30}'
# log: Plan POSTPAID_599 changed -> evicted 2 related subscriber-details entries
curl localhost:8080/api/v1/subscribers/9123456780/details        # plan.monthlyFee = 649.00
```

This is automated in `SubscriberCachingTest.tariffChangeEvictsRelatedSubscriberDetails`.

**Design alternative for very large plans** (millions of subscribers): don't embed. Compose the view at read time from two independently cached entries (`subscribers` + `tariff-plans`). Each changes on its own, with no fan-out eviction. Embedding is acceptable only when the dependent set is small or the TTL is short (here 2m).

## B12. Can you provide an example of implementing a custom cache manager in a Spring REST application to handle caching with a specific strategy?

**Strategy: two-level "near cache".** It combines Caffeine L1 per pod and Redis L2 shared, with pub/sub invalidation. A popular plan read 5,000 times per second is served from local memory, while consistency comes from Redis.

**The manager** (`cache/TwoLevelCacheManager.java`) extends `AbstractTransactionSupportingCacheManager` to get transaction awareness for free:

```java
public class TwoLevelCacheManager extends AbstractTransactionSupportingCacheManager {

    @Override
    protected Collection<? extends Cache> loadCaches() {
        List<Cache> caches = new ArrayList<>();
        for (String name : remote.getCacheNames()) {
            TwoLevelCache cache = new TwoLevelCache(name, local.getCache(name), remote.getCache(name), publisher);
            twoLevelCaches.put(name, cache);
            caches.add(cache);
        }
        return caches;
    }

    public void evictLocal(String cacheName, String key) { ... }   // used by the pub/sub listener
    public void clearLocal(String cacheName) { ... }
}
```

**The cache** (`cache/TwoLevelCache.java`) implements `org.springframework.cache.Cache`:

```java
@Override
public ValueWrapper get(Object key) {
    ValueWrapper value = local.get(localKey(key));
    if (value != null) { l1Hits.increment(); return value; }
    value = remote.get(key);
    if (value != null) { l2Hits.increment(); local.put(localKey(key), value.get()); return value; }
    misses.increment();
    return null;
}

// @Cacheable(sync = true): one loader per key per pod, L2 checked before the DB
@Override
public <T> T get(Object key, Callable<T> valueLoader) { ... }
```

**Wiring** (`config/TwoLevelCacheConfig.java`):

- Builds the Redis manager (L2) and the Caffeine manager (L1 with TTL capped at `l1-ttl`).
- Registers the pub/sub listener container.
- Exports `telecom.cache.two.level.gets{result=l1_hit|l2_hit|miss}` metrics.

Activate it with `--spring.profiles.active=redis,two-level`. Service code is unchanged.

Other strategies you could build the same way:

- Tenant-aware managers for MVNOs (prefix per tenant).
- A read-through manager that loads from a remote BSS.
- A circuit-breaking manager that bypasses Redis when it's unhealthy.

## B13. How would you handle cache expiration or refresh in a Spring REST application when the underlying data changes? Explain with a demonstration.

Match the strategy to **where the change comes from**:

| Source of change | Strategy | In this project |
|---|---|---|
| Through this API | Write-through `@CachePut` or `@CacheEvict` in the same method | `changePlan`, `updatePricing`, `updateStatus`, `terminate` |
| Changes affecting other regions | Domain event, then evict after commit | `RelatedCacheInvalidator` |
| Periodic upstream publication (e.g. nightly catalogue) | Scheduled `@CachePut` refresh plus warm-up at startup | `CatalogCacheRefresher` |
| Ad-hoc by operations | Admin refresh or evict endpoint | `POST /api/v1/admin/caches/plan-catalog/refresh` |
| Another system writes the DB directly | Consume its events (Kafka/CDC) and evict | Extension snippet below |
| Anything missed | TTL safety net | `telecom.cache.caches.*.ttl` |

**Warm-up and scheduled refresh** (`cache/CatalogCacheRefresher.java`):

```java
@EventListener(ApplicationReadyEvent.class)
public void warmUp() { tariffPlanService.refreshActiveCatalog(); }

@Scheduled(cron = "${telecom.cache.catalog-refresh-cron:0 */10 * * * *}")
public void scheduledRefresh() { tariffPlanService.refreshActiveCatalog(); }

// TariffPlanService
@CachePut(cacheNames = CacheNames.PLAN_CATALOG, key = "'ACTIVE'")
public List<TariffPlanDto> refreshActiveCatalog() { return loadActiveCatalog(); }
```

`@CachePut` replaces the value in place, so readers never hit an empty cache during refresh.

**External changes via Kafka** (extension: add `spring-kafka` and this listener):

```java
@KafkaListener(topics = "crm.subscriber-changed", groupId = "telecom-cache-service")
public void onCrmChange(SubscriberChangedEvent event) {
    String msisdn = Msisdn.normalize(event.msisdn());
    Objects.requireNonNull(cacheManager.getCache(CacheNames.SUBSCRIBERS)).evict(msisdn);
    Objects.requireNonNull(cacheManager.getCache(CacheNames.SUBSCRIBER_DETAILS)).evict(msisdn);
}
```

With Redis as the shared cache, one consumer group is enough. With pure local caches, every pod needs its own group ID.

**Demonstration:**

```bash
curl localhost:8080/api/v1/plans                       # served from warmed cache (no MISS log)
curl -X PUT localhost:8080/api/v1/plans/PREPAID_299/pricing -H 'Content-Type: application/json' \
     -d '{"monthlyFee":319,"dataGb":56,"validityDays":28}'
curl localhost:8080/api/v1/plans                       # catalogue evicted -> reloaded with 319
curl -X POST localhost:8080/api/v1/admin/caches/plan-catalog/refresh   # manual refresh
```

## B14. Can you explain the process of configuring cache regions or namespaces in a Spring REST application with Redis caching? Provide a sample implementation.

**Regions** are the cache names. Each region is an isolated set of keys with its own policy:

```java
// config/CacheNames.java
public static final String TARIFF_PLANS = "tariff-plans";
public static final String PLAN_CATALOG = "plan-catalog";
public static final String SUBSCRIBERS = "subscribers";
public static final String SUBSCRIBER_DETAILS = "subscriber-details";
```

Each region gets its own TTL, size and serializer (`RedisCacheConfig` with `withInitialCacheConfigurations`). `disableCreateOnMissingCache()` ensures a typo in a cache name fails fast instead of silently creating a region with default policy.

**Namespaces** are key prefixes that isolate this application, this schema version and this region inside a shared Redis:

```java
// TelecomCacheProperties
public String keyPrefix(String cacheName) {
    return namespace + ":" + schemaVersion + ":" + cacheName + "::";
}
// RedisCacheConfig
.computePrefixWith(props::keyPrefix)
```

```text
telecom:v1:subscribers::9876543210
telecom:v1:tariff-plans::POSTPAID_599
telecom:v1:plan-catalog::ACTIVE
```

**Why namespaces matter in telecom:**

- **Shared Redis across teams.** Billing, CRM and self-care APIs can't collide, and ACLs can restrict each app to `~telecom:*` (A14).
- **Safe deployments.** Changing `TariffPlanDto` incompatibly? Set `schema-version: v2`. The new release reads and writes `telecom:v2:*`, and the old keys expire on their own. No flush and no deserialization errors.
- **Multi-tenancy (MVNOs).** Add the tenant to the prefix, e.g. `namespace + ":" + tenantId + ":"`, or to the key: `key = "#tenantId + ':' + #msisdn"`.
- **Targeted operations.** `redis-cli --scan --pattern 'telecom:v1:subscribers::*'` and `clear()` (SCAN) only touch one region.

Redis logical databases (`spring.data.redis.database`) are another form of isolation, but they are not supported in Redis Cluster. Prefixes are the portable choice.

## B15. How would you monitor the cache utilization and performance in a Spring REST application that uses Spring Cache or Redis caching? Explain with a demonstration.

**1. Expose Actuator and Prometheus** (`application.yml`):

```yaml
management:
  endpoints.web.exposure.include: health,info,metrics,caches,prometheus
  endpoint.health.show-details: always
```

**2. What to watch:**

| Metric | Source | Meaning |
|---|---|---|
| `cache_gets_total{result="hit"/"miss"}` | Caffeine and Redis | Hit ratio |
| `cache_puts_total`, `cache_evictions_total`, `cache_removals_total` | Caffeine / Redis | Churn; rising evictions mean the cache is too small |
| `cache_size` | Caffeine | Utilisation versus `max-size` |
| `telecom_cache_two_level_gets_total{result}` | Custom MeterBinder | L1 versus L2 versus miss |
| `http_server_requests_seconds` | Spring MVC | Latency effect |
| `hikaricp_connections_active` | DB pool | Downstream relief |
| Redis `used_memory`, `evicted_keys`, `keyspace_hits/misses`, `connected_clients` | `INFO` or redis_exporter | Server-side health |
| `health.redis` | `/actuator/health` | Connectivity |

**3. Demonstration:**

```bash
# generate traffic
for i in $(seq 1 50); do curl -s localhost:8080/api/v1/subscribers/9876543210 > /dev/null; done

curl localhost:8080/api/v1/admin/caches/subscribers
#  {"cacheName":"subscribers","provider":"caffeine","hits":49,"misses":1,"hitRatio":0.98,"estimatedSize":1,...}

curl 'localhost:8080/actuator/metrics/cache.gets?tag=cache:subscribers&tag=result:hit'
curl localhost:8080/actuator/caches
curl localhost:8080/actuator/health                # includes redis status in the redis profile
curl -s localhost:8080/actuator/prometheus | grep -E '^cache_(gets|puts|size|evictions)'

docker exec -it telecom-redis redis-cli INFO stats | grep -E 'keyspace_(hits|misses)|evicted_keys'
docker exec -it telecom-redis redis-cli INFO memory | grep used_memory_human
```

**4. Grafana panels and alerts:**

- Hit ratio per region. Alert when below 80% for 10 minutes; a sudden drop often means a key bug or a mass eviction.
- Cache size versus max size.
- Eviction rate.
- p95 latency of cached endpoints.
- Redis memory versus `maxmemory`. Alert at 85%.
- Redis connection errors, which show up as `LoggingCacheErrorHandler` WARN logs and should be turned into a log-based alert.

## B16. Showing the project structure for a Telecom application, provide a complete Spring REST "Telecom" application implementation that does the caching of data in Spring REST applications using Spring Cache. Implement Controller, Service and Persistence layers. Provide the details of the properties configuration and dependencies to be added to this application.

The complete implementation is this project. See `README.md` for the full tree and run instructions. Summary of the layers:

**Controller layer** (`controller/`):

- `TariffPlanController` (`/api/v1/plans`): catalogue, plan by ID with ETag, create, reprice, retire.
- `SubscriberController` (`/api/v1/subscribers`): get, details, register, change plan, change status, terminate. MSISDN normalisation, `no-store`.
- `CacheAdminController` (`/api/v1/admin/caches`): stats, evict key, clear region, refresh catalogue.

**Service layer** (`service/`), where all cache annotations live:

- `TariffPlanService`: `@Cacheable(sync)`, `@CachePut`, `@Caching`, `@CacheEvict`, domain event.
- `SubscriberService`: `@Cacheable(unless)`, `@CachePut`, `@Caching(put+evict)`, `@CacheEvict` on multiple regions.

**Persistence layer** (`repository/`, `domain/`):

- `TariffPlanRepository`, `SubscriberRepository` (Spring Data JPA).
- Entities `TariffPlan`, `Subscriber` with `@Version` optimistic locking.
- H2 by default, PostgreSQL with the `postgres` profile. Seed data in `data.sql` / `data-postgres.sql`.

**Cache infrastructure** (`config/`, `cache/`, `monitoring/`): Caffeine, Redis, two-level, Sentinel/Cluster, error handling, typed serializers, related invalidation, warm-up/refresh, statistics.

**Dependencies** (`pom.xml`):

| Dependency | Why |
|---|---|
| `spring-boot-starter-web` | REST controllers |
| `spring-boot-starter-validation` | Request validation |
| `spring-boot-starter-data-jpa`, `h2`, `postgresql` | Persistence layer |
| `spring-boot-starter-cache` | Spring Cache abstraction |
| `caffeine` | In-memory provider / L1 |
| `spring-boot-starter-data-redis`, `commons-pool2` | Redis provider / L2, pub/sub, pooling |
| `spring-boot-starter-actuator`, `micrometer-registry-prometheus` | Monitoring |
| `spring-boot-starter-test` | Caching tests |

**Properties** (`src/main/resources`):

- `application.yml`: datasource and JPA, `telecom.cache.*` (namespace, schema version, default TTL, L1 TTL, per-region TTL/size, refresh cron), Actuator exposure, logging.
- `application-redis.yml`: Redis host/port/credentials/TLS/timeouts/pool, Redis health.
- `application-sentinel.yml` and `application-cluster.yml`: HA topologies.
- `application-postgres.yml`: shared DB for multi-instance runs.
