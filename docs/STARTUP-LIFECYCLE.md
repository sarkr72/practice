# Startup lifecycle — how the bean graph and proxies actually form

Companion to `REQUEST-LIFECYCLE.md`. That doc covered a request; this one zooms
into **Act 1** — what `SpringApplication.run()` does between process start and
"ready to serve", and *why* the proxies, conditional beans, and per-profile
shape come out the way they do. Cites real files.

---

## 0. The trigger

`EmsApplication.main()` (`EmsApplication.java:24`) calls
`SpringApplication.run(EmsApplication.class, args)`. Everything below is that
one call.

---

## 1. Environment + profile resolution (happens first, before any bean)

Spring builds an `Environment` from layered property sources, highest wins:

```
OS env vars (SPRING_PROFILES_ACTIVE=dev set by the ECS task def)
  > application-<profile>.yml
  > application.yml
  > application.properties defaults
```

- `SPRING_PROFILES_ACTIVE` is injected by the Spinnaker task definition
  (`spinnaker/pipelines/ems-deploy-cicd.json:108`, `"value": "dev"`). That single
  env var decides which `application-<profile>.yml` overlays load.
- If unset (local Maven run), `application.yml:13-14` defaults the profile to
  `local`.

**Why it matters:** the profile chosen here flips `ems.kafka.enabled` and the
`spring.autoconfigure.exclude` list, which in turn decides *which beans exist at
all* (section 4). Profile resolution is the root cause of the app being a
different shape in dev vs prod.

---

## 2. Classpath scan → bean *definitions* (not yet instances)

`@SpringBootApplication` (`EmsApplication.java:15`) = `@ComponentScan` +
`@EnableAutoConfiguration` + `@SpringBootConfiguration`.

- `@ComponentScan` walks `com.ems.ems.*` and registers a `BeanDefinition` for
  every `@RestController` / `@Service` / `@Component` / `@Configuration` /
  `@Repository`. At this point they're just *recipes*, nothing is instantiated.
- `@EnableAutoConfiguration` adds hundreds more candidate definitions from
  starter jars (Tomcat, Hibernate, Hikari, Jackson, Kafka, Redis, Actuator…),
  each guarded by `@Conditional`.

---

## 3. `@Conditional` evaluation — the gate that decides what's real

Before instantiating anything, Spring evaluates conditions against the
`Environment` from section 1. The codebase uses three flavors:

| Condition | Where | Effect |
|---|---|---|
| `@ConditionalOnProperty("ems.kafka.enabled"=true)` | `KafkaConfig.java:38`, `KafkaTopicsConfig.java:18`, `DepartmentEventProducer.java:17`, `DepartmentEventConsumer.java:20` | In `dev` (`ems.kafka.enabled: false` in `application-dev.yml`) **none of these beans are created**. The whole Kafka subsystem evaporates. |
| `@ConditionalOnBean(RedisConnectionFactory.class)` | `RedisConfig.java:65,80` | The `RedisTemplate` and Redis `CacheManager` only build if a Redis connection factory exists. In dev, Redis auto-config is excluded (`application-dev.yml:24-27`), so these don't load — and the cache silently falls back to `simple` (in-memory `ConcurrentMapCacheManager`). |
| `@ConditionalOnClass` (inside Boot's own auto-config) | framework | Tomcat/Hibernate exist because their classes are on the classpath. |

**This is the answer to "why doesn't dev crash without Kafka/Redis."** The beans
that need them are conditionally absent, and the *consumers* of those beans are
written to tolerate absence:

```java
// DepartmentServiceImpl.java:30  — ObjectProvider, not a hard dependency
private final ObjectProvider<DepartmentEventProducer> eventProducerProvider;
// :102  — ifAvailable() no-ops when the bean was never created
eventProducerProvider.ifAvailable(p -> p.publish(event));
```

---

## 4. Context refresh, in phases (the real ordering)

`AbstractApplicationContext.refresh()` runs these phases in order. The
**ordering is the point** — it's why proxies exist before your beans are used.

**4a. BeanFactoryPostProcessors run.** These mutate bean *definitions*.
`@EnableConfigurationProperties(KafkaProperties.class)` (`KafkaConfig.java:37`)
binds the `ems.kafka.*` YAML tree (`application.yml:96-126`) into the
`KafkaProperties` record here.

**4b. BeanPostProcessors register.** Your `@Enable*` switches on
`EmsApplication.java:16-20` each contribute a `BeanPostProcessor` that will wrap
beans as they're created:

| Switch | Registers | Wraps |
|---|---|---|
| `@EnableTransactionManagement` (:19) | `InfrastructureAdvisorAutoProxyCreator` | `@Transactional` beans |
| `@EnableCaching` (:16, also `CacheConfig.java:13`) | cache advisor | `@Cacheable/@CacheEvict` beans |
| `@EnableAsync` (:17, also `AsyncConfig.java:14`) | async advisor | `@Async` methods |
| `@EnableRetry` (:20) | retry advisor | `@Retryable` methods |
| `@EnableJpaAuditing` (`JpaAuditingConfig.java:18`) | auditing listener wiring | `@CreatedBy/@CreatedDate` on entities |

**4c. Singleton instantiation + DI + proxy wrapping.** Now beans are built in
dependency order. For each bean, after construction the post-processors from 4b
get a chance to return a *replacement* — a CGLIB proxy. So:

```
new DepartmentServiceImpl(repo, providerObjectProvider)   // real object
   → post-processor sees @Transactional (class) + @CacheEvict (methods)
   → returns DepartmentServiceImpl$$SpringCGLIB$$abc123    // proxy stored in context
```

`DepartmentController` (`DepartmentController.java:30`) is injected with the
**proxy**, never the raw object. (Full call-time consequence is Act 3 of
`REQUEST-LIFECYCLE.md`.)

Spring Data repositories are also born here: `DepartmentRepository`
(`DepartmentRepository.java:12`) is an interface — the
`JpaRepositoryFactoryBean` generates a `SimpleJpaRepository`-backed proxy and
parses derived query methods (`existsByNameIgnoreCase` →
`SELECT … WHERE UPPER(name)=UPPER(?)`).

---

## 5. Datastore bring-up (ordered, before traffic)

**5a. DataSource + Flyway.** Hikari builds the pool from the profile's
`spring.datasource.*` (`application-dev.yml:8-19`). Flyway then runs
(`application.yml:43-46`) and applies
`src/main/resources/db/migration/V1__initial_schema.sql`.

**5b. Hibernate validate.** `ddl-auto: validate` (`application.yml:33`) makes
Hibernate compare `@Entity` mappings (`Department.java`, `Employee.java`,
`Project.java`, `BaseEntity.java`) against the now-migrated schema and **fail
fast** if they drift. Hibernate never creates tables here — Flyway owns schema.

**5c. Kafka topics (prod/perf only).** If `KafkaTopicsConfig` loaded (section 3),
its `NewTopic` beans (`KafkaTopicsConfig.java:31,45`) are picked up by the
auto-configured `KafkaAdmin`, which creates `ems.department.events.v1` and its
`.DLT` if absent.

---

## 6. SmartLifecycle start (listeners + servers come online)

After all singletons exist, `SmartLifecycle` beans start in phase order:

- **Kafka listener containers** (prod/perf): the
  `ConcurrentKafkaListenerContainerFactory` (`KafkaConfig.java:133`) spins up
  `concurrency=3` (`application.yml:116`) polling threads bound to
  `DepartmentEventConsumer.consume` (`DepartmentEventConsumer.java:40`). Manual
  ack mode (`KafkaConfig.java:145`) means offsets commit only on
  `ack.acknowledge()`.
- **`@Async` executor**: `AsyncConfig.java:18` initializes the `ems-async-`
  thread pool.
- **Tomcat** binds `:8080` (`application.yml:53`) last.

---

## 7. Ready

`@PostConstruct initTimeZone()` (`EmsApplication.java:27`) already pinned the JVM
to UTC during bean init. Once Tomcat is up, the Actuator readiness group
(`application.yml:77-81` → `readinessState,db,redis`; dev narrows it to
`readinessState,db` at `application-dev.yml:44-49`) reports UP. The ALB target
group health check (`/actuator/health`, `terraform/ems/alb.tf:31`) starts
passing → traffic flows.

---

## 8. Shutdown (the mirror image)

On SIGTERM (ECS task stop, or a red/black cutover deregistering the old group):

1. Tomcat stops accepting new connections; in-flight requests get
   `server.shutdown: graceful` (`application.yml:54`), up to 30s
   (`application.yml:49-50`).
2. Kafka containers stop polling and commit final offsets.
3. The `@Async` pool drains (`AsyncConfig.java:26-27`,
   `waitForTasksToCompleteOnShutdown=true`).
4. Hikari closes connections.

---

## One-glance startup order

```
main() → SpringApplication.run
  1. resolve Environment + active profile  (SPRING_PROFILES_ACTIVE)
  2. component scan + auto-config → bean DEFINITIONS
  3. evaluate @Conditional → prune beans (Kafka/Redis may vanish)
  4. refresh():
       a. BeanFactoryPostProcessors (bind @ConfigurationProperties)
       b. register BeanPostProcessors (the @Enable* proxy makers)
       c. instantiate singletons → inject → WRAP in CGLIB proxies
  5. DataSource → Flyway migrate → Hibernate validate → Kafka topics
  6. SmartLifecycle: Kafka listeners → async pool → Tomcat :8080
  7. readiness UP → ALB health passes → serving
```

**The mental model:** conditions (step 3) decide *what exists*; post-processors
(step 4b) decide *what gets wrapped*; the order guarantees every proxy is in
place before step 6 lets the first request or Kafka record in.
