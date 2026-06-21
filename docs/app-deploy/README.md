# App deploy — README

This folder explains how a `git push` to `main` becomes a running app on
ECS Fargate. The deployer is **GitHub Actions calling `aws ecs` directly**
— a Spinnaker pipeline runs in parallel for learning but is **not** in
the deploy path.

| File | Read it when |
|---|---|
| `INSTRUCTIONS.md` | Wiring up GitHub Actions. ~5 minutes of config. |
| `DEBUG.md` | A deploy failed. Every error class with the why. |
| `README.md` | You want to understand the workflow file + task def. (Here.) |

---

## What this layer is

```
git push origin main
   │
   ▼
.github/workflows/deploy.yml   ← GitHub Actions
   • test → Jib build → ECR push
   • look up AWS account id (for the task def template)
   • render ecs/taskdef.dev.json.tpl with new image
   • aws ecs register-task-definition       (new revision)
   • aws ecs update-service --force-new-deployment
   • aws ecs wait services-stable
   │
   ▼
ECS Fargate   rolling deploy, ALB serves the new version
```

The service itself is created and owned by Terraform (`terraform/ems/
service.tf`); this workflow only rolls a new task-def revision onto it.
Terraform ignores the service's `task_definition`, so the rollout sticks and is
never reverted. Each push gets a fresh OIDC credential from AWS — no static keys
stored in GitHub.

---

## File-by-file

### `.github/workflows/deploy.yml`

The whole workflow, ~12 steps, all in one file. Breaking it down by section:

#### Triggers + permissions (lines 22–28)
- `on: push: branches: [main]` — only `main` deploys. Other branches do
  nothing.
- `on: workflow_dispatch` — lets you trigger a manual run from the Actions
  tab.
- `permissions: id-token: write` — required for OIDC. GitHub injects the
  `ACTIONS_ID_TOKEN_REQUEST_*` env vars only when this is set.
- `concurrency.group: deploy-main` — one deploy at a time. Newer pushes
  don't cancel in-flight rollouts (default behavior; ECS handles overlap
  via its deployment controller anyway).

#### Top-level env vars
Knobs you can change to point this workflow at a different cluster:
```yaml
CLUSTER:   ems-dev
SERVICE:   ems-dev
CONTAINER: ems
ECR_REPO:  ems
```
(`TARGET_GROUP` and `SG_NAME` are gone — that networking moved onto the
Terraform-owned service.) Change any of these and the workflow re-targets
without touching shell
code below.

#### Step 1: Checkout (line 47)
Standard. `fetch-depth: 0` so the `git-commit-id` Maven plugin can stamp
the commit time into the Docker image's reproducible build metadata.

#### Step 2: Set up JDK + Maven cache (lines 52–57)
Standard. The Maven cache makes subsequent runs ~2 min faster.

#### Step 3: AWS OIDC login (lines 59–63)
`aws-actions/configure-aws-credentials@v4`. This is the credential magic:
1. GitHub mints a signed JWT identifying this workflow run.
2. The action calls AWS STS `AssumeRoleWithWebIdentity`, passing the JWT.
3. AWS validates the JWT against the GitHub OIDC provider registered in
   `terraform/spinnaker/github_oidc.tf`.
4. STS returns temporary credentials (1 hour) for the
   `github-actions-ems` role.
5. The action exports them as env vars for the rest of the job.

Zero static AWS keys in GitHub. The trust is scoped to
`repo:sarkr72/practice:ref:refs/heads/main` so even a stolen GitHub token
can't assume this role from a different branch or repo.

#### Step 4: ECR login (lines 65–67)
`aws-actions/amazon-ecr-login@v2` calls `aws ecr get-login-password`,
writes the result to `~/.docker/config.json`. Outputs `registry` (your
ECR registry hostname).

#### Step 5: Tests (line 70)
`./mvnw -B -ntp test`. Surefire (unit + slice). JaCoCo enforces ≥70%
line coverage — fails the build below that.

#### Step 6: Jib build + push (lines 72–76)
`./mvnw compile jib:build` — Jib assembles OCI layers directly from
compiled classes and dependency jars, pushes to ECR. Two tags: the
commit SHA (for traceability) and `latest`. No Docker daemon on the
runner needed.

#### Step 7: Discover AWS account
Looks up only the AWS account ID (via `aws sts get-caller-identity`), written
to `$GITHUB_OUTPUT` for the task-def render step. The VPC / subnets / security
group / target group lookups are gone — that wiring now lives on the
Terraform-owned service (`terraform/ems/service.tf`), so the deploy workflow no
longer touches networking at all.

#### Step 8: Render task definition (lines 103–110)
Reads `ecs/taskdef.dev.json.tpl`, replaces `__ACCOUNT__` with the looked-up
account ID and `__IMAGE__` with the freshly-pushed image URI, writes the
result to `taskdef.json`. Standard `sed` substitution — no envsubst needed.

#### Step 9: Register the new task definition (lines 112–119)
`aws ecs register-task-definition --cli-input-json file://taskdef.json`.
Returns the new task definition ARN (`...:task-definition/ems-dev:N`),
captured as `td_arn` for the next step.

#### Step 10: Roll out the new task definition
One command — `aws ecs update-service --force-new-deployment` — pointing the
Terraform-owned service at the freshly registered task-def revision.
- `--force-new-deployment` makes ECS roll the service even if the only change
  is the task def revision. (Without it, an update with no other field change
  is a no-op.)
- There's no `create-service` branch any more: the service always exists,
  because Terraform created it. Everything that used to be passed to
  `create-service` (launch type, network config, ALB mapping, 60s health-check
  grace period, `maxPercent=200 / minHealthyPercent=100`) now lives in
  `terraform/ems/service.tf` and is owned there.

#### Step 11: Wait for stable (lines 151–155)
`aws ecs wait services-stable` blocks until the rollout completes. Polls
ECS for ~10 minutes; succeeds when `desiredCount == runningCount` and the
deployment status is `PRIMARY`. If a task fails to come up healthy, this
exits non-zero and the job fails.

#### Step 12 (optional): Notify Spinnaker (lines 157–172)
Only runs if `vars.SPINNAKER_GATE_URL` is set. Posts a webhook event with
the parameters Spinnaker's `ems-deploy-dev-only` pipeline expects.
**`continue-on-error: true`** — a Spinnaker outage or pipeline failure
won't fail the deploy. This is for learning; the real deploy already
succeeded above.

#### Step 13: Summary (lines 174–180)
Writes a clean summary to `$GITHUB_STEP_SUMMARY` so the Actions UI shows
the deployed image and task definition ARN.

---

### `ecs/taskdef.dev.json.tpl`

The task definition template. Two placeholders:
- `__ACCOUNT__` — substituted with your account ID.
- `__IMAGE__` — substituted with the full ECR image URI (registry/ems:sha).

Key sections:

| Section | What it does |
|---|---|
| `family: "ems-dev"` | Task definition family name. Revisions accumulate as `ems-dev:1`, `ems-dev:2`, etc. — that's how rollback works. |
| `networkMode: "awsvpc"` + `requiresCompatibilities: ["FARGATE"]` | Fargate-only task with its own ENI. |
| `cpu: "512"` + `memory: "1024"` | 0.5 vCPU, 1 GB RAM per task. Tune for prod. |
| `executionRoleArn` | The `ems-dev-task-exec` role — used by the ECS agent (NOT by the app) to pull from ECR and read secrets. |
| `taskRoleArn` | The `ems-dev-task` role — the app's runtime identity. Currently empty (no perms). Add S3/SQS/etc. permissions here as the app grows. |
| `containerDefinitions[0].image` | The image URI (substituted). |
| `containerDefinitions[0].environment` | `SPRING_PROFILES_ACTIVE=dev` so Spring Boot picks `application-dev.yml`. |
| `containerDefinitions[0].secrets` | ECS resolves these from SSM/Secrets Manager at task start and injects as env vars: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` (SSM); `DB_PASSWORD` (Secrets Manager). The `application-dev.yml` references these as `${DB_HOST}` etc. |
| `containerDefinitions[0].logConfiguration` | `awslogs` driver → `/ecs/ems-dev` CloudWatch log group, stream prefix `ecs`. |

---

### `.github/workflows/infra.yml`

Manual button — runs Terraform without ever firing on a push.

| Section | What it does |
|---|---|
| `on: workflow_dispatch` | The **only** trigger. Clicked from Actions → "infra" → Run workflow. |
| `inputs.stack` | Dropdown: `ems` or `spinnaker`. |
| `inputs.action` | Dropdown: `plan` (safe preview) or `apply` (real changes). |
| `inputs.env` | Dropdown: `dev` / `perf` / `prod`. Only relevant for the `ems` stack. |
| `permissions.id-token: write` | OIDC, like deploy.yml. |
| Step: AWS OIDC login | Uses `github-actions-ems-infra` role (separate from deploy role — broader perms). |
| Step: `terraform plan` or `apply -auto-approve` | Runs the chosen action against the chosen stack/env. |

This file lets you run all infra changes from GitHub without your laptop —
audit log, no local AWS creds needed.

---

### `spinnaker/pipelines/ems-deploy-dev-only.json`

The optional Spinnaker pipeline for learning. **Not the deployer.** When
the workflow's optional notify step runs, this pipeline runs in parallel
on Spinnaker's side and deploys to a *separate stack* (`ems-spinnaker-vNNN`)
on the **canary target group** so it can't disturb the real `ems-dev`
service deployed above.

What it does at a high level:
1. Webhook trigger fires from `deploy.yml`'s notify step.
2. Spinnaker resolves the `subnetType: "ecs-tasks-dev"` to subnet IDs via
   the `immutable_metadata` tag created by
   `terraform/ems/spinnaker_subnet_tags.tf`.
3. Spinnaker creates an ECS service `ems-spinnaker-vNNN` on the canary
   target group with red/black strategy.

Without the subnet tag, this pipeline NPEs (the long debugging saga). With
the tag, it works — but it's still not the path your real users hit.

---

## Workflow architecture — who calls what, when, why

```
1. DEVELOPER (you):
   git push origin main
   Why: triggers the whole automated deploy.

2. GITHUB:
   - matches deploy.yml's "on: push: branches: [main]"
   - schedules the build-and-deploy job on an ubuntu-latest runner
   - generates a signed OIDC JWT identifying this workflow run.
   Why: every push to main = a new deploy attempt, with a fresh signed
   credential the workflow can present to AWS.

3. RUNNER (GitHub-hosted, ephemeral Ubuntu VM):
   - checks out the repo
   - sets up JDK 21
   - calls aws-actions/configure-aws-credentials → STS validates the JWT
     against the GitHub OIDC provider in your AWS account, returns temp
     credentials for the github-actions-ems role.
   - calls aws-actions/amazon-ecr-login → ECR auth in ~/.docker/config.json.
   Why: zero stored AWS keys; everything is short-lived.

4. MAVEN (running on the runner):
   - mvn test            → JUnit + JaCoCo coverage gate
   - mvn compile jib:build → Jib pushes the image to ECR.
   Why: tests must pass before image; Jib means no Docker daemon needed.

5. AWS CLI (running on the runner, as github-actions-ems):
   - aws sts get-caller-identity → AWS account id (for the task def template).
   - sed-substitute ecs/taskdef.dev.json.tpl with account + image URI.
   - aws ecs register-task-definition → new revision (ems-dev:N).
   - aws ecs update-service --force-new-deployment → point the
     Terraform-owned ems-dev service at the new revision.
   - aws ecs wait services-stable → block until rollout passes ALB health.
   Why: Terraform owns the service's shape; the runner just swaps in the new
   task def and ECS does the actual rolling deploy.

6. ECS (in AWS, autonomous):
   - reads the new task definition.
   - launches new Fargate tasks (2x desired).
   - registers them to the ALB target group ems-dev-stable.
   - waits for ALB to mark them healthy (GET /actuator/health → 200).
   - drains tasks from the previous revision.
   - reports deployment status PRIMARY when stable.
   Why: ECS's deployment controller handles the rolling deploy semantics
   (max 200% / min 100%) so the workflow doesn't have to.

7. (Optional) NOTIFY SPINNAKER (still on the runner):
   - if SPINNAKER_GATE_URL is set, curl POSTs to Spinnaker's Gate.
   - Spinnaker's ems-deploy-dev-only pipeline fires in parallel.
   Why: learning. continue-on-error means even if Spinnaker's broken,
   the deploy that just succeeded above stays succeeded.
```

---

## Trust chain summary

```
GitHub Action signs JWT
    │ "I am sarkr72/practice, branch main, run #12345"
    ▼
AWS STS validates JWT vs GitHub OIDC provider in your account
    │ checks: aud=sts.amazonaws.com, sub matches repo:sarkr72/practice:ref:refs/heads/main
    ▼
STS returns temp creds for github-actions-ems role
    │ valid 1 hour
    ▼
deploy.yml uses creds to:
    │ - push to ECR ems repo
    │ - describe networking
    │ - register task def (account-wide on ecs:RegisterTaskDefinition)
    │ - create/update ecs:Cluster/ems-* services
    │ - PassRole ems-*-task* roles to ECS
    │ - (the policy can't do anything else)
    ▼
ECS uses ems-dev-task-exec role to pull image + fetch secrets
ECS uses ems-dev-task role for the running container (empty for now)
```

Three IAM identities, each scoped to exactly what it needs. No shared keys.

---

## Boundary with other folders

```
docs/spinnaker-infra/           docs/app-infra/             docs/app-deploy/ (here)
   creates github-actions-ems   creates the empty             reads outputs from both,
   role + OIDC trust            ECS cluster, ALB, RDS,        creates the running service.
                                IAM, secrets, subnet tags
   provides: AWS_ROLE_ARN       provides: cluster, SG, TG     consumes: everything above
   (a Terraform output)         names (Terraform outputs)
   stored in GitHub variables   read live by deploy.yml       turns it into
   on push                                                    aws ecs commands
```
