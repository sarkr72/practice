# Round 5 — ECS Fargate + Spinnaker (the JPMC-shaped path)

This round commits to ECS Fargate as the deploy target and reworks the
Spinnaker pipeline to use ECS server groups (red/black). Terraform stops
managing ECS services and task definitions — Spinnaker owns those now.

## Ownership boundary (the big idea)

```
TERRAFORM owns:           SPINNAKER owns:
- ALB + listener          - ECS task definitions (versioned per deploy)
- Target groups           - ECS services         (versioned per deploy)
- ECS cluster             - Traffic shifting between server groups
- IAM roles
- CloudWatch log groups
- RDS, ECR, Secrets
```

Re-applying terraform never replaces a running service. Re-running Spinnaker
never modifies infrastructure.

## Files changed

| File                                            | What changed |
|-------------------------------------------------|--------------|
| `spinnaker/pipelines/ems-deploy-cicd.json`      | Full rewrite. K8s `deployManifest` stages → ECS `createServerGroup` with `redblack`. Canary uses a separate target group. |
| `spinnaker/README.md`                           | Rewritten for ECS context (subnetType, ECS accounts, target group naming). |
| `terraform/main.tf`                             | Removed `aws_ecs_task_definition`, `aws_ecs_service`, autoscaling. Added `aws_lb_target_group.canary` and a host-header listener rule. Tasks SG renamed to a stable contract name. |
| `terraform/secrets.tf`                          | Rewritten flat. SSM Parameters for non-sensitive config (DB_HOST, DB_PORT, DB_NAME, DB_USERNAME), Secrets Manager for actual secrets (DB_PASSWORD, JWT_SECRET). One IAM read-policy per env, attached to task-execution role. |
| `terraform/rds.tf`                              | Updated to write the master password into the new `aws_secretsmanager_secret.db_password` resource (was previously a `for_each` map). |
| `terraform/variables.tf`                        | Removed `var.config` (per-service map). Removed `image_tag` (Spinnaker handles the image now). |
| `terraform/variables/dev.tfvars`                | Slimmed — no more service config block. |
| `terraform/variables/prod.tfvars`               | Slimmed — no more service config block. |
| `terraform/outputs.tf`                          | Now exposes the values Spinnaker needs to reference: target group ARNs/names, IAM role ARNs/names, security group IDs/names, subnet IDs, log group, ECR URI, SSM/Secrets ARNs. |
| `scripts/deploy.sh`                             | No longer takes an image tag. Just `terraform apply`. App deploys go through Spinnaker. |
| `jules.yml`                                     | Added `deploy.platform: ecs-fargate` for clarity. |
| `CICD.md`                                       | Rewritten flow diagram + ownership boundary section. |
| `README.md`                                     | Updated stack table and deploy section. |

## What's gone (deliberately)

- **`aws_ecs_task_definition` and `aws_ecs_service` resources.** Spinnaker
  creates these on its first run (`createServerGroup`). Versioned
  automatically: `ems-dev-v000`, `ems-dev-v001`, ...
- **Autoscaling resources.** Out of scope; can be reintroduced via Spinnaker
  pipeline's autoscaling stage, or as separate `aws_appautoscaling_target`
  resources keyed off the latest server group name.
- **`var.config` per-service map** in tfvars. Was overengineered for a
  single-service repo. If you add a second service later, copy this whole
  module rather than parameterizing.
- **`image_tag` terraform variable.** The image is no longer terraform's
  concern.

## What stays the same

- Jenkins, Jules, Jib, all five scans, JaCoCo coverage gate, NVD API key
  wiring, distroless image, reproducible Jib builds.
- The webhook contract from Jenkins to Spinnaker. Jenkins POSTs the same
  parameters; only the Spinnaker stages downstream changed.
- Local dev (`local-up.sh`, `local-down.sh`, docker-compose).
- Test layout (Surefire / Failsafe split, MariaDB Testcontainer, IT split).

## Migration steps if you've already applied the old terraform

1. `terraform plan` — you'll see the ECS service and task definition marked
   for destroy. That's expected.
2. Manually drain traffic / scale to 0 if the old service has live traffic
   you care about.
3. `terraform apply` — destroys the old service, creates the new target
   groups, secrets paths, etc.
4. Import the new Spinnaker pipeline (`spin pipeline save --file ...`).
5. Trigger a build → Spinnaker creates the first ECS server group from
   scratch.

## Caveats — read before running

1. **JWT_SECRET must be populated out-of-band in prod** before the prod
   pipeline runs, or the task will fail to start. See `CICD.md` pre-flight
   checklist step 9.
2. **Spinnaker subnet attribute names** (`ecs-tasks-dev`, `ecs-tasks-prod`)
   must be registered in clouddriver before the pipeline can deploy.
3. **Listener `default_action` is 100% stable.** Canary takes no real
   traffic by default — only synthetic probes via the canary host header.
   Edit `terraform/main.tf` to use a weighted forward action if you want
   real traffic shifting.
4. **JPMC's actual Jules schema is internal.** The keys in `jules.yml` are
   modeled on the public pattern. Your org's Jules may want different field
   names — rename in `jules.yml` AND in Jenkinsfile's `readYaml` references.

---

# Round 4 fixes — review pass on the round 3 bundle

The round 3 bundle had a handful of bugs that would have broken things in
practice. This round fixes them in place; nothing was redesigned.

## Bugs fixed

- **MySQL port mismatch.** Compose maps `3307:3306`, but `application-local.yml`
  and `local-up.sh`'s announce message both said `3306`. Aligned everything on
  `localhost:3307`.
- **Spinnaker pipeline ran a `findImageFromTags` stage but never used the
  result.** Stages 2/5/7 hardcoded `ACCOUNT_ID.dkr.ecr...` instead. Reworked
  the JSON: dropped the dead stage, switched to a parameterized
  `${parameters.ecrRegistry}/${parameters.ecrRepo}:${parameters.imageTag}`,
  and added matching pipeline parameters. (Round 5 then replaced this entire
  pipeline for the ECS rework.)
- **`Dockerfile` `USER 1000` on `gcr.io/distroless/java21-debian12:nonroot`.**
  Distroless `:nonroot` is UID 65532. Updated `--chown` and `USER` to match,
  and the matching `<user>` in pom.xml's Jib config too.
- **`pom.xml` Jib `creationTime: USE_CURRENT_TIMESTAMP`** broke the
  reproducible-image claim. Switched to `${git.commit.time}`. Same for the
  `org.opencontainers.image.created` label.
- **`outputs.tf` said "Postgres".** Engine is MySQL. Fixed the descriptions.
- **Jenkinsfile shared-library import would fail at parse time on Jenkins
  installs without the library.** Commented out by default.
- **Jenkinsfile Integration Tests stage had redundant skip flags.** Cleaned up.
- **`jules.yml` declared a 70% coverage threshold but nothing enforced it.**
  Added a JaCoCo `check` execution to pom.xml.
- **`.dependency-check-suppressions.xml`** referenced by pom.xml didn't exist.
  Added an empty placeholder with usage docs.
- **`dependency-check` 10+ throttles to 30+ min without an NVD API key.**
  Added `nvd-api-key` Jenkins credential.
- **Spinnaker JSON / README / Jenkinsfile all referenced `spinnaker/...`
  paths but the files were flat at the repo root.** Moved.
- **No real top-level README.** Added one.
- **`global.auto.tfvars` had a real-looking 12-digit AWS account ID
  committed.** Replaced with `account.auto.tfvars.example`.
- **`env` variable / `deploy.sh` accepted `test` but no `test.tfvars` exists.**
  Trimmed to `dev|prod`.

## Doc fixes

- CICD.md and CHANGES-cicd.md no-docker-socket framing corrected — Trivy and
  Testcontainers still need the socket. Reworded to "Jib doesn't fork to
  docker for the build itself."
- spinnaker/README.md rewritten for the parameterized pipeline.
