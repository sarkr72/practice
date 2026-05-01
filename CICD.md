# CI/CD Architecture

End-to-end picture of how a commit becomes a running task on ECS Fargate.

## Tools and what each one does

| Tool        | Role                                                       |
|-------------|-----------------------------------------------------------|
| **Jules**   | Pipeline-as-config (`jules.yml`). Source of truth for app metadata, scan thresholds, and Spinnaker pipeline name. |
| **Jenkins** | Pipeline-as-code shell (`Jenkinsfile`). Reads `jules.yml`, runs CI stages (test, scan, build, push). |
| **Jib**     | Container image builder. Talks directly to ECR — no `docker build` in CI. |
| **Trivy / Sonar / Dependency-Check / gitleaks / tfsec** | Security gates. Each fails the build on policy violation. |
| **Spinnaker** | Continuous Delivery on ECS Fargate. Owns dev → judgment → prod canary → prod stable. Triggered by Jenkins webhook. |
| **ECR**     | Container registry. Provisioned by Terraform (`terraform/ecr.tf`). |
| **ECS Fargate** | Where Spinnaker deploys. Cluster + IAM + ALB provisioned by Terraform; ECS services + task definitions are owned by Spinnaker. |

## Ownership boundary

```
+------------------------- TERRAFORM owns -------------------------+
|                                                                  |
|  ALB, listener, target groups (stable + canary)                  |
|  ECS cluster, IAM roles (task + task-execution)                  |
|  CloudWatch log groups                                           |
|  RDS MySQL, security groups                                      |
|  ECR repo                                                        |
|  Secrets Manager (DB_PASSWORD, JWT_SECRET)                       |
|  SSM Parameters (DB_HOST, DB_PORT, DB_NAME, DB_USERNAME)         |
|                                                                  |
+------------------------- SPINNAKER owns -------------------------+
|                                                                  |
|  ECS task definitions (per deploy, versioned)                    |
|  ECS services        (per deploy, versioned: ems-dev-v001, ...)  |
|  Traffic shifting between server groups                          |
|                                                                  |
+------------------------------------------------------------------+
```

The split: terraform creates the *platform*; Spinnaker creates the *application
runtime*. Re-applying terraform never replaces a running service. Re-running
Spinnaker never modifies infrastructure.

## The flow

```
Developer
   │
   │  git push
   ▼
Bitbucket / GitHub
   │
   │  webhook
   ▼
+----------------------------------------------------------+
| Jenkins (CI)                                             |
|                                                          |
|  Init  → Reads jules.yml                                 |
|  Tests → Unit (surefire) + Integration (failsafe + TC)   |
|  Scans → Sonar / DepCheck / gitleaks / tfsec  (parallel) |
|  Build → Jib pushes image straight to ECR                |
|  Trivy → Scans the pushed image                          |
|  POST  → Webhook to Spinnaker                            |
+----------------------------------------------------------+
                              │
                              │  X-Spinnaker-Token + parameters
                              ▼
+----------------------------------------------------------+
| Spinnaker (CD)                                           |
|                                                          |
|  Deploy to Dev (createServerGroup, redblack)             |
|  Smoke Test Dev                                          |
|  Manual Judgment        ──── only if branch=main         |
|  Deploy Prod Canary     (1 task, canary target group)    |
|  Bake 10 min                                             |
|  Deploy Prod Stable     (createServerGroup, redblack)    |
|  Destroy Canary  ┐                                       |
|  Smoke Test Prod ┘                                       |
+----------------------------------------------------------+
                              │
                              ▼
                        ECS Fargate
```

## Required Jenkins credentials

| ID                        | Type                | Used for                              |
|---------------------------|---------------------|---------------------------------------|
| `aws-account-id`          | Secret text         | `${ACCOUNT}.dkr.ecr.us-east-1...`     |
| `aws-ecr-creds`           | AWS credentials     | `aws ecr get-login-password` for Jib  |
| `sonar-token`             | Secret text         | Sonar analysis                        |
| `nvd-api-key`             | Secret text         | OWASP Dependency-Check (avoids 30+ min throttling) |
| `spinnaker-webhook-token` | Secret text         | `X-Spinnaker-Token` header            |

## Required Jenkins agent capabilities

- Linux + Docker socket mounted (Testcontainers + Trivy)
- Maven Wrapper (`./mvnw`) — already in repo
- `aws` CLI for ECR auth
- `trivy` binary on `PATH`

If you're running Jenkins on EKS, attach an IRSA role with ECR push perms
instead of using `aws-ecr-creds`. Replace the `withCredentials` block in the
`Build & Push Image (Jib)` stage with an unauthenticated call — Jib will
pick up IRSA automatically via the AWS SDK.

## Pre-flight checklist before the first end-to-end run

1. AWS state bucket + DynamoDB lock table exist (one-time, see `terraform/providers.tf`)
2. `./scripts/deploy.sh dev` succeeds → ALB, RDS, ECR, IAM, secrets all exist
3. Jenkins credentials configured (table above)
4. Spinnaker app `ems` created
5. Spinnaker ECS accounts (`aws-dev`, `aws-prod`) registered in clouddriver
6. Spinnaker subnet attributes (`ecs-tasks-dev`, `ecs-tasks-prod`) registered
7. `spin pipeline save --file spinnaker/pipelines/ems-deploy-cicd.json`
8. Spinnaker webhook token configured in `echo.yml`
9. `JWT_SECRET` populated in Secrets Manager for prod (out-of-band):
   ```
   aws secretsmanager put-secret-value \
     --secret-id /prod/image-uploader/JWT_SECRET \
     --secret-string "$(openssl rand -base64 64)"
   ```
10. Push a commit to `develop` branch → watch Jenkins → watch Spinnaker → see task in ECS console

## Replacing the bake stage with Kayenta

If your Spinnaker has Kayenta installed, replace the "Bake Canary (10 min)"
wait stage with a real `canaryAnalysis` stage comparing canary metrics to
baseline. Wait is the fallback.

## Files now superseded by Jib

The repo still has `Dockerfile`, `.dockerignore`, `scripts/build-and-push.sh`,
and `docker-compose.yml`. With Jib, only the compose files are still useful
(local dev). Recommendations:

| File                            | Keep / Remove                                            |
|---------------------------------|----------------------------------------------------------|
| `Dockerfile`                    | Keep as fallback (local image build for debugging). Jenkinsfile no longer uses it. |
| `.dockerignore`                 | Keep — Jib doesn't read it but `docker build` still works for local use. |
| `scripts/build-and-push.sh`     | Keep but mark deprecated. CI uses Jib now. |
| `docker-compose.yml` + override | Keep — unchanged use for local dev. |
