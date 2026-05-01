# EMS — Employee Management System

Spring Boot 3.3 reference service. Built as a learning project for enterprise
patterns: layered config, Flyway migrations, Kafka-based events with DLT,
Redis-backed sessions, full testing pyramid (unit + slice + Testcontainers IT),
and a Jenkins → Jib → Spinnaker CI/CD pipeline targeting ECS Fargate.

## Stack

| Concern        | Choice                                                       |
|----------------|--------------------------------------------------------------|
| Language       | Java 21                                                      |
| Framework      | Spring Boot 3.3 (web, data-jpa, security, batch, kafka)      |
| DB             | MySQL 8 (RDS in dev/prod, MariaDB 5s container in tests)     |
| Migrations     | Flyway                                                       |
| Cache/Session  | Redis (ElastiCache in prod; disabled in dev profile)         |
| Events         | Kafka (MSK with IAM auth in prod)                            |
| Build          | Maven Wrapper                                                |
| Container      | Jib in CI, Dockerfile fallback for local debug               |
| Infra          | Terraform (ECS Fargate platform; Spinnaker owns services)    |
| CI / CD        | Jenkins → Jib → ECR → Spinnaker → ECS Fargate                |
| Observability  | Actuator, Micrometer (Prometheus), structured JSON logs      |

## Quick start (local dev)

```bash
./scripts/local-up.sh                                    # mysql + redis + kafka via compose
./mvnw spring-boot:run -Dspring-boot.run.profiles=local  # run app on host
```

The app comes up on `http://localhost:8080`. Actuator health at
`/actuator/health`, Swagger UI at `/swagger-ui.html`.

When done:
```bash
./scripts/local-down.sh
```

## Deploy

Two-step ownership model:

**1. Platform (terraform).** Provisions the immutable bits: ALB, target groups,
ECS cluster, IAM, RDS, ECR, secrets. Run once per environment, re-run when
infra changes:
```bash
./scripts/deploy.sh dev
./scripts/deploy.sh prod
```

**2. Application (Spinnaker).** Provisions ECS services and task definitions.
Triggered automatically by every push to `develop` or `main` via Jenkins.
First time: import the pipeline once with `spin pipeline save`. Details in
[`spinnaker/README.md`](spinnaker/README.md).

CI/CD architecture and end-to-end flow are documented in [`CICD.md`](CICD.md).

## Tests

```bash
./mvnw test                          # unit + slice; JaCoCo enforces ≥70% line coverage
./mvnw verify                        # also runs Testcontainers IT
./mvnw verify -DskipUnitTests=true   # IT only — what Jenkins's IT stage runs
```

## Project layout

```
ems/
├── README.md                    ← this file
├── CICD.md                      ← CI/CD architecture
├── CHANGES-cicd.md              ← change history
├── jules.yml                    ← pipeline-as-config (source of truth for thresholds)
├── Jenkinsfile                  ← CI shell, reads jules.yml
├── pom.xml
├── Dockerfile                   ← fallback / local debug; CI uses Jib
├── docker-compose.yml           ← local dev backing services
├── docker-compose.override.yml  ← compose-only sanity check of prod image
│
├── scripts/                     ← deploy / smoke-test helpers
├── spinnaker/                   ← Spinnaker pipeline JSON + import docs
├── terraform/                   ← AWS platform (ECS Fargate)
└── src/                         ← Spring Boot app
```
