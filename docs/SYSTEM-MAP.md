# System map — how all the files talk to each other (app + infra)

The other three docs follow *time* (startup, a request, a deploy). This one
follows *contracts*: which file depends on which, the exact string/name/type
that couples them, and what breaks if you change one side without the other.

A "contract" here = a value that must match in two places the compiler can't
check for you. Those are where real systems break.

---

## 1. The big picture — five layers, who calls whom

```
┌─ CI/CD ─────────────────────────────────────────────────────────────┐
│ .github/workflows/deploy.yml ── triggers ──► Spinnaker pipeline JSON │
│        │ assumes role                              │ deploys          │
│        ▼                                            ▼                  │
│ terraform/spinnaker/github_oidc.tf        terraform/spinnaker/* (EKS) │
└──────────────────────────────────────────────────────────────────────┘
            │ pushes image                                │ reads names
            ▼                                             ▼
┌─ PLATFORM (terraform/ems/*) ─ ECR, ALB, ECS cluster, IAM, RDS, secrets┐
└──────────────────────────────────────────────────────────────────────┘
            │ provides env vars (DB_*, SPRING_PROFILES_ACTIVE)
            ▼
┌─ APP RUNTIME (src/main) ─ Spring Boot ───────────────────────────────┐
│ Controller → Service(proxy) → Repository → Entity → MySQL            │
│                     └─► Producer → Kafka topic → Consumer → Redis     │
└──────────────────────────────────────────────────────────────────────┘
            │ schema
            ▼
┌─ DATA ─ Flyway migration ↔ JPA entities ↔ RDS MySQL ─────────────────┐
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. App-layer contracts (within `src/main`)

### 2a. The request call chain
```
DepartmentController.java   (@RestController, /api/v1/departments)
   │ holds DepartmentService (interface)  — injected as a CGLIB proxy
   ▼
DepartmentService.java      (interface — the seam)
   ▲ implemented by
DepartmentServiceImpl.java  (@Service, @Transactional, @Cacheable/@CacheEvict)
   │ holds DepartmentRepository
   ▼
DepartmentRepository.java   (interface extends JpaRepository — Spring generates impl)
   ▼
Department.java / BaseEntity.java  (@Entity → table `departments`)
```
**Why the interface (`DepartmentService`) exists:** it's the proxy seam.
`@EnableTransactionManagement`/`@EnableCaching` wrap the impl; the controller
depends on the interface so it transparently gets the proxy. (See
`REQUEST-LIFECYCLE.md` Act 3.)

### 2b. DTO ↔ Entity ↔ Event (three shapes of the same data)
| Shape | File | Used at the boundary of |
|---|---|---|
| `DepartmentDto` | `dtos/DepartmentDto.java` | HTTP (Jackson in/out) + Redis cache value |
| `Department` | `entities/Department.java` | JPA / MySQL |
| `DepartmentEvent` | `events/DepartmentEvent.java` | Kafka wire format |

`DepartmentServiceImpl.toDto()` (`:105`) is the **only** translation point
entity→DTO. `DepartmentEvent.created/updated/deleted` (`DepartmentEvent.java:25-38`)
build events from saved entities. Keep these in sync by hand — nothing enforces
that `DepartmentDto.name` and `Department.name` agree.

### 2c. The Kafka topic-name contract (a classic break, already fixed once)
```
Producer  DepartmentEventProducer.java:38  →  KafkaTopicsConfig.DEPARTMENT_EVENTS_TOPIC
Consumer  DepartmentEventConsumer.java:36   →  KafkaTopicsConfig.DEPARTMENT_EVENTS_TOPIC
Topic def KafkaTopicsConfig.java:21         =  "ems.department.events.v1"
```
All three reference the **same constant** (`KafkaTopicsConfig.java:21`). The
comment at `DepartmentEventConsumer.java:33` notes this used to be a hardcoded
string that drifted from the producer — exactly the bug the shared constant
prevents.

### 2d. Config classes → property tree
`KafkaConfig.java:37` `@EnableConfigurationProperties(KafkaProperties.class)`
binds the `ems.kafka.*` YAML (`application.yml:96-126`) into the
`KafkaProperties` record, which `KafkaConfig`, `KafkaTopicsConfig`, and the
error handler all read. Change a key name in YAML → must change the record.

### 2e. Conditional wiring (what makes profiles differ)
```
application-<profile>.yml  sets  ems.kafka.enabled  and  spring.autoconfigure.exclude (redis)
        │
        ▼ read by @ConditionalOnProperty / @ConditionalOnBean
KafkaConfig, KafkaTopicsConfig, DepartmentEventProducer/Consumer  (exist or not)
RedisConfig beans (exist or not)
        │
        ▼ tolerated by
DepartmentServiceImpl  (ObjectProvider.ifAvailable, :102)
```

---

## 3. App ↔ Infra contract: environment variables

The app reads env vars; the ECS task definition (Spinnaker) supplies them; the
*values* come from Terraform-created SSM/Secrets. Three files, one chain:

```
terraform/ems/secrets.tf   creates  /<env>/ems/DB_HOST, DB_PORT, DB_NAME, DB_USERNAME (SSM)
                                     /<env>/ems/DB_PASSWORD (Secrets Manager)
        │ referenced by path
        ▼
spinnaker/pipelines/ems-deploy-cicd.json:111-117   "secrets":[{valueFrom:"/dev/ems/DB_HOST"}…]
        │ ECS injects as env vars at task start
        ▼
src/main/resources/application-dev.yml:8-11        url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}
                                                    username: ${DB_USERNAME}  password: ${DB_PASSWORD}
```
Plus the profile selector:
`SPRING_PROFILES_ACTIVE=dev` (pipeline JSON `:108`) → picks
`application-dev.yml`. Change the env name on either side and the app starts
with an unresolved `${...}` and dies.

---

## 4. Infra ↔ Infra contract: resource names referenced by string

Spinnaker's pipeline JSON references AWS resources **by name**, and Terraform
creates them with exactly those names via `local.app = "ems"`
(`terraform/ems/main.tf:18`) + `var.env`. This is the most fragile coupling in
the repo — pure strings, no validation.

| Name in pipeline JSON | Created by | Resource |
|---|---|---|
| `ems-dev` (`ecsClusterName`, :73) | `terraform/ems/ecs.tf:6` | ECS cluster |
| `ems-dev-stable` (`targetGroup`, :91) | `terraform/ems/alb.tf:24` | ALB target group |
| `ems-dev-canary` (prod canary stage) | `terraform/ems/alb.tf:43` | ALB target group |
| `ems-dev-tasks` (`securityGroups`, :96) | `terraform/ems/network.tf:32` | security group |
| `ems-dev-task-exec` (`iamRole`, :98) | `terraform/ems/iam.tf:21` | task-execution role |
| `ems-dev-task` (`taskRoleArn`, :99) | `terraform/ems/iam.tf:31` | task role |
| `/ecs/ems-dev` (`awslogs-group`, :121) | `terraform/ems/ecs.tf:28` | CloudWatch log group |
| `ems` (`ecrRepo` param) | `terraform/ems/ecr.tf:7` | ECR repository |
| `/dev/ems/DB_*` (`secrets`, :112-116) | `terraform/ems/secrets.tf` | SSM + Secrets Manager |

`terraform/ems/outputs.tf` exists precisely to surface these so you can paste
them into Spinnaker without guessing. The headers in `alb.tf:9-12`,
`network.tf:5-6`, `iam.tf:4-6` all warn: *don't rename without updating the
pipeline.*

`subnetType: ecs-tasks-dev` (pipeline `:95`) is different — it's not a Terraform
name but a **clouddriver-registered subnet attribute**; see
`spinnaker/README.md`.

---

## 5. CI/CD ↔ Infra contracts

### 5a. GitHub Actions ↔ AWS (trust, not keys)
```
.github/workflows/deploy.yml:41  role-to-assume: ${{ vars.AWS_ROLE_ARN }}
        │ must equal
terraform/spinnaker/github_oidc.tf  output github_actions_role_arn (:96)
        │ and the JWT's sub must match
github_oidc.tf:59  "repo:sarkr72/practice:ref:refs/heads/main"
        │ which must match the GitHub repo + branch the workflow runs on
deploy.yml:14  branches: [main]
```
And the repo name itself: `github_oidc.tf:20` `github_repo = "sarkr72/practice"`
must equal the actual repo. (Full mechanics in `DEPLOY-LIFECYCLE.md` §1.)

### 5b. GitHub Actions ↔ Spinnaker (webhook)
```
deploy.yml:58  POST $SPINNAKER_GATE_URL/webhooks/webhook/ems-build-complete
        │ path segment must equal
pipeline JSON:49  "source": "ems-build-complete"
        │ body parameters must match
pipeline JSON:36-43  parameterConfig (imageTag, branch, ecrRegistry, ecrRepo…)
```
`SPINNAKER_GATE_URL` (a GitHub variable) must point at the live Gate
LoadBalancer from `kubectl -n spinnaker get svc spin-gate`.

### 5c. Spinnaker ↔ AWS (IRSA)
```
terraform/spinnaker/eks.tf       creates the EKS cluster + OIDC provider
terraform/spinnaker/iam.tf:31    role "spinnaker-managed" (ECS/ELB/S3/PassRole)
        │ trust: any SA in the spinnaker namespace (iam.tf:20)
        ▼ annotated onto the SA
manifests/spinnaker/spinnakerservice.yaml:115  eks.amazonaws.com/role-arn: __SPINNAKER_ROLE_ARN__
        │ which clouddriver assumes to call ECS createServerGroup
        ▼ and passes the app's task roles (PassRole scoped to ems-*-task* in iam.tf:64-66)
```
Note `iam.tf:64-66` scopes `PassRole` to `ems-*-task` / `ems-*-task-exec` — that
arn glob is a contract with `terraform/ems/iam.tf:21,32`.

### 5d. Terraform state contract (both roots, one bucket)
```
terraform/ems/providers.tf:36       bucket rinku-tfstate-001, key ems/...
terraform/spinnaker/providers.tf:20 bucket rinku-tfstate-001, key spinnaker/...
```
Same S3 bucket + DynamoDB lock table (`terraform-locks`), different keys, so the
two roots have independent state but share the backend you bootstrap once.

---

## 6. Data-layer contract: migration ↔ entities

```
src/main/resources/db/migration/V1__initial_schema.sql   defines tables/columns
        ↕ must agree (Hibernate fails startup if not — ddl-auto: validate)
entities/*.java  (@Column names, @Table names, types)
```
`BaseEntity.java:48-62` columns (`created_at`, `updated_at`, `created_by`,
`version`) must exist in every table the migration creates. `@Version` (`:44`)
requires a `version` column; `AuditingEntityListener` writes `created_by` via
`JpaAuditingConfig.auditorAware()`.

---

## 7. The "alternative path" files (Jenkins) — how they relate

```
jules.yml  ── read by ──►  Jenkinsfile  ── builds image, POSTs ──► same Spinnaker webhook
   │ (app metadata, scan thresholds, coverage gate)
   └─ coverage-threshold:70 (jules.yml:51)  mirrors  jacoco check in pom.xml
```
These are **parallel** to `.github/workflows/deploy.yml` — same job (build →
ECR → webhook), different CI engine. You run one or the other, not both
(`CICD.md` banner). `Jenkinsfile.perf` + `performance/*` are the load-test
subtree the perf pipeline stage invokes.

---

## 8. Quick "if I change X, what else must change?" table

| Change | Must also update |
|---|---|
| `local.app` in `terraform/ems/main.tf` | every name in pipeline JSON §4 table + `ecrRepo` param |
| `var.env` adds a new env | new `envs/<env>.tfvars`, new SSM paths, pipeline stage, `application-<env>.yml` |
| A `DB_*` env var name | `secrets.tf` + pipeline JSON `secrets` + `application-*.yml` `${...}` |
| Kafka topic name | only `KafkaTopicsConfig.java:21` (producer/consumer auto-follow the constant) |
| Coverage threshold | `pom.xml` jacoco `check` **and** `jules.yml:51` |
| GitHub repo / branch | `github_oidc.tf:20,59` + `deploy.yml:14` |
| Spinnaker Gate URL | `SPINNAKER_GATE_URL` GitHub variable |
| A DTO field | `Department` entity + `V1__…sql` (if persisted) + `DepartmentEvent` (if emitted) |

---

## The one rule behind all of it

The compiler checks the app layer. **Nothing checks the seams between layers** —
env-var names, AWS resource names, the webhook path, the topic string, the OIDC
`sub`. Those are string contracts maintained by convention (`local.app="ems"` +
`var.env`) and documented warnings in the file headers. This doc is the index of
those seams; when something "works locally but breaks deployed," it's almost
always one of the §3–§5 contracts.
