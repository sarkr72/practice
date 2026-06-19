# How this app actually runs — the parts you can't see

End-to-end execution trace, using a single `POST /api/v1/departments` request as
the vehicle. The focus is the **invisible machinery**: proxies, filter chains,
auto-configuration, and advice ordering that you never see in the source but
that actually runs. Every claim cites real files in this repo.

---

## ACT 0 — Before any Java runs: how the process even starts

The container's entrypoint (set by Jib in `pom.xml`) is the JVM launching the
class with `@SpringBootApplication`. ECS Fargate starts the task -> the
distroless image runs roughly:

```
java -XX:MaxRAMPercentage=75 ... org.springframework.boot.loader... com.ems.ems.EmsApplication
```

So the very first line of *your* code that executes is `EmsApplication.main()`
at `src/main/java/com/ems/ems/EmsApplication.java:23`.

---

## ACT 1 — `main()` -> the entire app is built in memory (the bean graph)

```java
SpringApplication.run(EmsApplication.class, args);   // EmsApplication.java:24
```

One line, enormous amount of hidden work:

**1. `@SpringBootApplication` is decomposed** into three meta-annotations:
- `@SpringBootConfiguration` — this class itself is a bean factory.
- `@ComponentScan` — scans `com.ems.ems.*` for `@Component/@Service/@RestController/@Repository`.
  This is *how* `DepartmentController`, `DepartmentServiceImpl`,
  `DepartmentEventProducer` get discovered — nobody registers them by hand.
- `@EnableAutoConfiguration` — reads `META-INF/spring/...AutoConfiguration.imports`
  from every jar on the classpath and conditionally activates config.

**2. Auto-configuration fires conditionally.** `spring-boot-starter-web` on the
classpath -> `DispatcherServletAutoConfiguration` + an embedded **Tomcat**.
`spring-boot-starter-data-jpa` present -> Hibernate, a HikariCP `DataSource`,
and an `EntityManagerFactory` are built. Each is gated by
`@ConditionalOnClass` / `@ConditionalOnProperty` — the same mechanism your own
`DepartmentEventProducer` uses at line 17:

```java
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
```

> **Invisible consequence:** in the `dev` profile, `application-dev.yml` sets
> `ems.kafka.enabled: false`. So the Kafka producer bean is **never created**.
> That's why `DepartmentServiceImpl` injects it as
> `ObjectProvider<DepartmentEventProducer>` (line 30), not directly — a hard
> `@Autowired` would crash startup when the bean is absent. More on this in Act 6.

**3. Your `@Enable*` switches install proxy infrastructure.** Each annotation
on `EmsApplication` (lines 16-20) registers a `BeanPostProcessor` that will
*wrap your beans in proxies*:

| Annotation (EmsApplication.java) | What it silently installs |
|---|---|
| `@EnableTransactionManagement` | `InfrastructureAdvisorAutoProxyCreator` -> wraps `@Transactional` beans |
| `@EnableCaching`               | a cache advisor -> wraps `@Cacheable/@CacheEvict` beans |
| `@EnableAsync`                 | wraps `@Async` methods to run on a thread pool |
| `@EnableRetry`                 | wraps `@Retryable` methods |
| `@EnableScheduling`            | starts a scheduler thread |

**4. Bean instantiation + dependency injection.** Spring builds beans in
dependency order. `DepartmentController` needs `DepartmentService`, so
`DepartmentServiceImpl` is built first. Constructor injection — `DepartmentController.java:30`
is `private final DepartmentService departmentService`, populated by Lombok's
`@RequiredArgsConstructor` (line 27) generating the constructor at compile time.

**5. The critical invisible step — proxy wrapping.** When `DepartmentServiceImpl`
is created, the post-processors from step 3 see its class-level `@Transactional`
(line 23) and method-level `@Cacheable/@CacheEvict` (lines 39, 54, 73, 90) and
**replace the bean in the context with a CGLIB proxy subclass**. So
`DepartmentController` does *not* hold a `DepartmentServiceImpl` — it holds a
`DepartmentServiceImpl$$SpringCGLIB$$...` that wraps it. **This is the single
most important invisible fact in the whole app**, and Act 3 shows why.

**6. Flyway runs.** Before the app accepts traffic, `application.yml:43-46`
triggers Flyway, which executes `src/main/resources/db/migration/V1__initial_schema.sql`
against MySQL. Hibernate then *validates* (not creates) the schema because
`application.yml:33` sets `ddl-auto: validate`.

**7. `@PostConstruct initTimeZone()` runs** (`EmsApplication.java:27`) — pins
the JVM to UTC before any request.

**8. Tomcat opens port 8080** (`application.yml:53`) and the readiness probe
at `/actuator/health/readiness` flips to UP. Only now does the ALB route traffic.

---

## ACT 2 — A request arrives: the filter chain (pure invisible plumbing)

`POST /api/v1/departments` hits Tomcat. Before *any* of your code runs, the
bytes pass through a **servlet filter chain**:

```
TCP :8080
  -> Tomcat connector (parses HTTP)
    -> OncePerRequestFilter chain:
        - CharacterEncodingFilter
        - Spring Security's FilterChainProxy   <- your SecurityConfig lives here
        - ...
      -> DispatcherServlet  (the Spring MVC "front controller")
```

**Spring Security** (`SecurityConfig.java:90`) is active. Your `filterChain`
bean (line 93) built these rules: `/api/**` is `.permitAll()` (line 115),
CSRF disabled (line 97), sessions `STATELESS` (line 100). So the security
filter waves the request through without auth — but it *did* run, and it's
where a JWT check would slot in later. (Lines 1-59 are a commented-out earlier
version; the **active** class starts at line 60.)

**`DispatcherServlet`** then does handler mapping: it matches
`POST /api/v1/departments` against `@RequestMapping("/api/v1/departments")`
(`DepartmentController.java:26`) + `@PostMapping` (line 32) and resolves the
target method `createDepartment`. Still no business code yet.

---

## ACT 3 — Into the controller, then the proxy stack (the heart of it)

`DispatcherServlet` now prepares to call `createDepartment(DepartmentDto)`.
Two invisible things happen *before* your method body:

**1. Body -> object (Jackson).** The `@RequestBody` (line 34) triggers
`MappingJackson2HttpMessageConverter` to deserialize the JSON into a
`DepartmentDto`. Jackson uses the Lombok-generated no-args ctor + setters
(`DepartmentDto.java:11-15`).

**2. Validation.** `@Valid` (line 34) runs Hibernate Validator against the DTO.
`DepartmentDto.java:21` has `@NotBlank` on `name`. If name is blank, **your
controller body never executes** — validation throws
`MethodArgumentNotValidException`, caught by
`GlobalExceptionHandler.handleValidationErrors` (`GlobalExceptionHandler.java:55`),
returns 400. That's why there's no manual null-check in the controller.

Now `createDepartment` (line 36) calls:

```java
departmentService.createDepartment(departmentDto);
```

Here's the invisible reality — **`departmentService` is the CGLIB proxy from
Act 1 step 5**, not the real object. The call doesn't go straight to your
code. It passes through a stack of "around" interceptors, each of which runs
code *before and after* your method:

```
controller calls departmentService.createDepartment(dto)
        |
        v
+------------------------------------------------+
| CGLIB proxy: DepartmentServiceImpl$$SpringCGLIB |
|                                                 |
|  (1) TransactionInterceptor  (@Transactional, line 53)
|        -> opens a DB transaction
|        -> binds a Hibernate Session to the thread
|                                                 |
|  (2) CacheInterceptor        (@CacheEvict, line 54)
|        -> notes "evict cache 'departments' after success"
|                                                 |
|        +-------------------------------------+ |
|        | YOUR ACTUAL METHOD BODY runs here    | |  <- DepartmentServiceImpl.java:55
|        +-------------------------------------+ |
|                                                 |
|  (2) on return -> evicts all entries in cache 'departments'
|  (1) on return -> commits the transaction (or rolls back on exception)
+------------------------------------------------+
```

> **Subtle, genuinely-hidden detail:** the class is annotated
> `@Transactional(readOnly = true)` at the class level (line 23), and write
> methods *override* it with a plain `@Transactional` (lines 53, 72, 89) to
> get a read-write tx. So reads like `getDepartmentById` run in a read-only
> tx (Hibernate skips dirty-checking -> faster), and only writes get a
> flushing transaction. You never see this switch; the proxy picks the most
> specific annotation per method.
>
> **Honest caveat on ordering:** both the transaction and cache interceptors
> default to `Ordered.LOWEST_PRECEDENCE`, so the relative order of (1) and
> (2) isn't *contractually* guaranteed unless you set an explicit order. In
> practice for `@CacheEvict` (default `beforeInvocation=false`) the eviction
> happens after your body returns successfully — which is what you want here.

---

## ACT 4 — Your method body, line by line, and what each line really triggers

Inside `createDepartment` (`DepartmentServiceImpl.java:55`):

**Line 56** `departmentRepository.existsByNameIgnoreCase(...)`
`DepartmentRepository` (`DepartmentRepository.java:12`) is an **interface you
never implemented**. At startup, Spring Data JPA generated a proxy
(`SimpleJpaRepository` + a query derived from the method name).
`existsByNameIgnoreCase` is parsed into `SELECT ... WHERE UPPER(name)=UPPER(?)`.
-> SQL #1 to MySQL. If true -> `DuplicateResourceException` (line 57) ->
caught later by `GlobalExceptionHandler.java:47` -> HTTP 409.

**Lines 60-62** build a `Department` entity (Lombok `@Setter` from
`Department.java:18`).

**Line 63** `departmentRepository.save(entity)` — multiple invisible systems
fire at once:

- `@Id` is `GenerationType.IDENTITY` (`BaseEntity.java:41`) -> Hibernate must
  INSERT immediately to get the DB-assigned id -> SQL #2.
- **`AuditingEntityListener`** (registered via `@EntityListeners` on
  `BaseEntity.java:33`) intercepts the persist event and populates
  `@CreatedBy`/`@LastModifiedBy` (lines 56/61) by calling your
  `auditorAware()` bean (`JpaAuditingConfig.java:22`), which returns
  `"system"`. Wired by `@EnableJpaAuditing` (line 18).
- **Hibernate `@CreationTimestamp`/`@UpdateTimestamp`** (`BaseEntity.java:48/52`)
  set the timestamps.
- **`@Version`** (line 44) is initialized to 0 — optimistic-locking state used
  on future updates (if two updates race, the second gets
  `OptimisticLockingFailureException`, handled at `GlobalExceptionHandler`
  import line 8).

**Lines 65-66** `publish(DepartmentEvent.created(...))` -> see Act 6.

**Line 68** `toDto(saved)` maps entity -> DTO. Note line 110:
`entity.getEmployees().size()`. The `employees` list is a lazy `@OneToMany`
(`Department.java:30`). Accessing `.size()` *would* trigger a lazy-load SQL —
but here the entity was just created in the same transaction, so the
collection is an empty initialized list, no query. (In `getDepartmentById`
the same line *can* trigger a lazy SELECT — a hidden N+1 risk the IT tests
check for via `generate_statistics`.)

---

## ACT 5 — The transaction commit (still inside the proxy, after your body)

Your method returns the DTO. Control goes **up the proxy stack** (Act 3
diagram, return path):

1. `CacheInterceptor` evicts the `departments` cache (so a later
   `getAllDepartments` won't serve stale data).
2. `TransactionInterceptor` calls commit -> Hibernate **flushes** any pending
   state and the JDBC connection commits -> Hikari returns the connection to
   the pool.

If your body had thrown a `RuntimeException`, the interceptor would
`rollback()` instead — that's the default rule, and it's why you never write
try/catch around the DB code.

---

## ACT 6 — The Kafka publish: conditional, async, fire-and-forget

`publish()` (`DepartmentServiceImpl.java:101`):

```java
eventProducerProvider.ifAvailable(p -> p.publish(event));
```

`eventProducerProvider` is an `ObjectProvider`. **`ifAvailable` only runs the
lambda if the bean exists.** In `dev` (kafka disabled) the producer bean was
never created (Act 1, the `@ConditionalOnProperty`), so this is a **no-op** —
the app works fine with no Kafka. In `prod/perf` the bean exists and
`DepartmentEventProducer.publish` (`DepartmentEventProducer.java:32`) runs:

- Builds a `ProducerRecord` with the department id as the **partition key**
  (line 33) -> all events for one department land on the same partition ->
  ordering guarantee.
- Attaches `x-event-id` / `x-event-type` headers (lines 39-40) so ops can
  triage without deserializing JSON.
- `kafkaTemplate.send(record)` returns a `CompletableFuture` (line 42). The
  `whenComplete` callback (line 44) logs success/failure **on a Kafka I/O
  thread later** — your request thread does *not* wait for the broker ack.
  The HTTP response can return before the event is actually persisted in Kafka.

---

## ACT 7 — The response travels back out

`createDepartment` returns `ResponseEntity.status(CREATED).body(ApiResponse.ok(...))`
(`DepartmentController.java:37`). Back in `DispatcherServlet`:

- `ApiResponse.ok` (`ApiResponse.java:26`) wraps the DTO in the uniform
  envelope; `@JsonInclude(NON_NULL)` (line 16) drops null fields.
- Jackson serializes it to JSON. `application.yml:21-27` controls this:
  ISO-8601 dates, UTC.
- 201 + JSON flows back out through the same filter chain (reverse order) to
  Tomcat to the ALB to the client.

**Request thread is done.** Total DB round-trips: 2 (exists-check + insert).

---

## ACT 8 — Meanwhile, on a completely different thread: the consumer

Truly invisible from the request — happens asynchronously, possibly on a
different ECS task entirely.

At startup, `@KafkaListener` (`DepartmentEventConsumer.java:35`) caused Spring
to spin up a **listener container** with background polling threads
(concurrency 3, from `application.yml:116`). When the event lands:

1. The container deserializes JSON -> `DepartmentEvent` (Jackson uses the
   record's `@JsonCreator` ctor, `DepartmentEvent.java:20`) and invokes
   `consume` (line 40).
2. **Idempotency via Redis** (lines 47-48):
   `setIfAbsent(key, "1", 7-day TTL)` is an atomic Redis `SET NX`. If the key
   already existed (duplicate delivery — Kafka is at-least-once), `firstTime`
   is false -> it acks and skips (lines 50-55). This is how you survive
   redeliveries.
3. `process()` runs (line 69). On success -> `ack.acknowledge()` (manual
   commit of the offset). On failure -> it **deletes the Redis marker**
   (line 62) so a retry can reprocess, then rethrows -> the error handler
   (configured in `KafkaErrorHandlingConfig`) retries with backoff and
   eventually routes to a **Dead-Letter Topic** consumed by
   `DepartmentEventDltConsumer`.

---

## The one-glance mental model

```
JVM start - main() - SpringApplication.run
   |  component scan finds your beans
   |  auto-config builds Tomcat + Hibernate + Hikari (conditionally)
   |  @Enable* installs BeanPostProcessors that WRAP beans in proxies   <- the secret
   |  Flyway migrates, ddl-auto validates, port 8080 opens
   v
HTTP POST - Tomcat - Filter chain (Security) - DispatcherServlet
   v
Controller (@Valid + Jackson run first)
   v
service PROXY:  [ open tx ] -> [ note cache-evict ] -> YOUR CODE -> [ evict ] -> [ commit ]
                                       |
                                       +- repo proxy -> Hibernate -> SQL -> MySQL
                                       +- AuditingEntityListener + @Version + timestamps fire on save
                                       +- ObjectProvider.ifAvailable -> Kafka send (async, prod only)
   v
ApiResponse envelope - Jackson - back through filters - 201
                                       :
        (separate thread, later)  Kafka listener -> Redis idempotency -> process -> ack / DLT
```

---

## The three things most people never see

1. **You're almost never calling your own object.** `departmentService` and
   `departmentRepository` are both proxies. Every `@Transactional`,
   `@Cacheable`, and derived-query behavior lives in the *wrapper*, which is
   why calling such a method *from inside the same class* (a "self-invocation")
   silently bypasses it — a classic bug.
2. **Conditional beans mean the app is a different shape per profile.** The
   Kafka producer/consumer literally don't exist in `dev`; `ObjectProvider`
   is the seam that makes that safe.
3. **A lot of "your" columns are written by listeners, not your code** — id,
   version, timestamps, created_by all appear via `save()` side-effects.
