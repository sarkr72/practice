# Deploy lifecycle — `git push` to a running ECS task, step by step

What actually happens between you pressing enter on `git push origin main` and a
new container serving traffic behind the ALB. Focus is the invisible handoffs:
the OIDC token exchange, what Jib does without Docker, and how Spinnaker turns
one webhook into a red/black ECS rollout. Cites real files.

---

## 0. The trigger

`git push origin main` updates the `main` ref on GitHub. GitHub matches it
against `.github/workflows/deploy.yml:13-14` (`on: push: branches: [main]`) and
schedules the `build-and-deploy` job on a fresh `ubuntu-latest` runner.

> Only `main` triggers it (workflow line 14). Pushing any other branch does
> nothing — by design, so feature branches don't deploy.

---

## 1. The OIDC token exchange (no AWS keys anywhere)

This is the part people find magical. Step by step:

1. The workflow declares `permissions: id-token: write`
   (`deploy.yml:17-19`). This makes GitHub inject two secret env vars into the
   runner: `ACTIONS_ID_TOKEN_REQUEST_URL` and `...TOKEN`.
2. `aws-actions/configure-aws-credentials@v4` (`deploy.yml:39-43`) calls that
   URL and receives a **short-lived signed JWT** from GitHub. Its claims include
   `aud=sts.amazonaws.com`, `sub=repo:sarkr72/practice:ref:refs/heads/main`.
3. The action calls AWS STS `AssumeRoleWithWebIdentity`, passing the JWT and
   the role ARN from `vars.AWS_ROLE_ARN`.
4. **AWS validates the JWT** against the OIDC provider you registered in
   `terraform/spinnaker/github_oidc.tf:33-37` — it fetches GitHub's public keys
   (the provider's thumbprint came from `tls_certificate.github`, line 26) and
   verifies the signature.
5. STS then checks the role's **trust policy**
   (`github_oidc.tf:39-62`): `aud` must equal `sts.amazonaws.com`
   (StringEquals, line 51) **and** `sub` must match
   `repo:sarkr72/practice:ref:refs/heads/main` (StringLike, line 59). A token
   from a different repo, a PR, or a non-main branch fails here.
6. On match, STS returns temporary credentials (1-hour). The action exports them
   as env vars for later steps.

The credential is scoped by `github_oidc.tf:66-83` to **only** ECR push on the
`ems` repo (line 80). It cannot touch anything else in the account.

---

## 2. Build + push the image with Jib (no Docker daemon)

**2a. ECR login.** `aws-actions/amazon-ecr-login@v2` (`deploy.yml:45-47`) runs
`aws ecr get-login-password | docker login`, which writes a base64 auth entry
into `~/.docker/config.json`. It also outputs `steps.ecr.outputs.registry` =
`<account>.dkr.ecr.us-east-1.amazonaws.com`.

**2b. Tests.** `./mvnw -B -ntp test` (`deploy.yml:49-50`) runs Surefire
(unit + slice). The JaCoCo `check` execution in `pom.xml` fails the build under
70% line coverage (the gate declared in `jules.yml:51`). If tests fail, nothing
deploys.

**2c. Jib build.** `./mvnw … compile jib:build` (`deploy.yml:52-55`):
- Jib does **not** call `docker build`. It assembles OCI layers directly from
  compiled classes + dependency jars, using the config in `pom.xml:257-289`
  (base `gcr.io/distroless/java21-debian12:nonroot`, port 8080, UID 65532,
  JVM flags).
- It reads ECR credentials from the `~/.docker/config.json` written in 2a.
- It **dedupes layers**: dependencies (which rarely change) are a separate layer
  from your app classes, so only changed layers upload. First push is slow;
  subsequent pushes are seconds.
- `-Djib.to.image=…/ems:${{ github.sha }}` tags the image with the commit SHA;
  `-Djib.to.tags=latest` adds `latest`.
- Reproducibility: `creationTime=${git.commit.time}` (`pom.xml:286`) means the
  same commit always produces a byte-identical image.

Result: `…/ems:<sha>` now exists in ECR. ECR's lifecycle policy
(`terraform/ems/ecr.tf:22-49`) will later expire untagged/old images.

---

## 3. Hand off to Spinnaker (one HTTP POST)

`deploy.yml:57-72` curls Spinnaker Gate:

```
POST  $SPINNAKER_GATE_URL/webhooks/webhook/ems-build-complete
body: { parameters: { imageTag=<sha>, branch=main,
                      ecrRegistry=<registry>, ecrRepo=ems, buildUrl=… } }
```

The path segment `ems-build-complete` matches the pipeline's webhook trigger
`source` (`spinnaker/pipelines/ems-deploy-cicd.json:45-52`). Spinnaker's `echo`
service receives it, `orca` starts a pipeline **execution**, binding the POSTed
`parameters` to the pipeline's `parameterConfig` (pipeline JSON lines 36-43).

The runner's job is now done. Everything downstream is Spinnaker.

---

## 4. Inside Spinnaker: the pipeline graph

Stages are a DAG linked by `requisiteStageRefIds`. The relevant ones:

```
Webhook
  → (refId 1)        Deploy to Dev          createServerGroup, ecs, aws-dev
  → (refId 2)        Smoke Test Dev         GET /actuator/health == 200
  → (perf-deploy)    Deploy to Perf         only if branch==main
  → (perf-smoke)     Smoke Test Perf        only if branch==main
  → (perf-loadtest)  BlazeMeter Load Test   only if branch==main  (type: jenkins)
  → (refId 3)        Manual Judgment        only if branch==main  (human approves)
  → (refId 4)        Deploy Prod Canary     1 task → canary target group
  → (bake)           Wait ~10 min
  → Deploy Prod Stable → Destroy Canary + Smoke Prod
```

`stageEnabled` expressions like `${parameters.branch == 'main'}`
(pipeline JSON lines 152-155) gate the perf/prod stages. **Since the GitHub
Actions workflow only fires on `main`, every auto-deploy attempts the full
chain** — see the caveat at the bottom.

---

## 5. What `createServerGroup` actually does (the ECS mechanics)

For "Deploy to Dev" (pipeline JSON lines 64-133), Spinnaker's `clouddriver`
(using the `spinnaker-managed` IAM role from
`terraform/spinnaker/iam.tf:31`, assumed via IRSA) performs:

1. **Register a task definition revision.** From the inline `containerDefinitions`
   (lines 101-127): image `…/ems:<sha>`, port 8080, env
   `SPRING_PROFILES_ACTIVE=dev` (line 108), and `secrets` pulled by **ARN/path**
   from SSM + Secrets Manager (lines 111-117): `/dev/ems/DB_HOST`,
   `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`. Logs → `/ecs/ems-dev`
   (line 121). Every one of those names is a **contract with Terraform**
   (`terraform/ems/secrets.tf`, `ecs.tf`) — see `SYSTEM-MAP.md`.
2. **Create a new ECS service** `ems-dev-vNNN` (versioned), launch type FARGATE,
   `desired=2` (line 128), networked into `subnetType: ecs-tasks-dev` (line 95)
   with security group `ems-dev-tasks` (line 96), task-exec role
   `ems-dev-task-exec` (line 98), task role `ems-dev-task` (line 99).
3. **Register tasks to the stable target group** `ems-dev-stable`
   (lines 87-93) — the same TG Terraform created (`terraform/ems/alb.tf:23`).

---

## 6. Inside one ECS task (where this doc meets the other two)

When the service launches a task, on each Fargate micro-VM:

1. The **task-execution role** (`ems-dev-task-exec`, created at
   `terraform/ems/iam.tf:20`, granted ECR + secrets read via
   `terraform/ems/secrets.tf`) authenticates the ECS agent to:
   - **pull** `…/ems:<sha>` from ECR,
   - **resolve the `secrets`**: ECS reads `/dev/ems/DB_*` from SSM/Secrets
     Manager and injects them as **environment variables** in the container.
2. The container starts → JVM → `EmsApplication.main()`. Now
   `STARTUP-LIFECYCLE.md` takes over: profile `dev` is active (from the env var
   in step 1 of section 5), `application-dev.yml` resolves `${DB_HOST}` etc.
   from those injected secrets, Flyway/Hibernate validate against RDS.
3. App reports readiness UP → the ALB health check on `ems-dev-stable`
   (`terraform/ems/alb.tf:30-37`, `/actuator/health`, 2 consecutive 200s) passes.

---

## 7. Red/black cutover

Once the new server group's tasks are healthy in the target group, `strategy:
redblack` (pipeline JSON line 129) makes Spinnaker:

1. Leave the new group serving (ALB now routes to its healthy tasks).
2. **Disable** the previous group `ems-dev-vNNN-1` (deregister its tasks from the
   TG; `deregistration_delay=30s` from `alb.tf:39` drains in-flight requests).
3. Keep `maxRemainingAsgs: 2` (line 130) — one old version stays *disabled* for
   instant rollback (re-enable it in the UI). Older ones are destroyed.

`rollback.onFailure: true` (line 131) means if the new tasks never go healthy,
Spinnaker auto-reverts to the previous group.

---

## End-to-end, one glance

```
git push origin main
  │
  ▼  GitHub matches deploy.yml (on: push main)
GitHub Actions runner
  │  OIDC JWT → STS AssumeRoleWithWebIdentity → temp creds   (github_oidc.tf trust)
  │  ECR login → mvn test (JaCoCo gate) → Jib build → push …/ems:<sha>  (no Docker)
  │  curl POST → Spinnaker Gate /webhooks/webhook/ems-build-complete
  ▼
Spinnaker (orca/clouddriver on EKS, IRSA → spinnaker-managed role)
  │  Deploy Dev: register taskdef (secrets by path) → create service ems-dev-vN
  │              → register to ems-dev-stable TG
  ▼
ECS Fargate task
  │  task-exec role: pull image, inject /dev/ems/* secrets as env
  │  JVM → Spring Boot (profile=dev) → Flyway/validate vs RDS → readiness UP
  ▼
ALB health check passes → red/black disables old group → live
```

---

## Honest caveats (so the doc matches reality)

1. **The pipeline still has Jenkins-flavored prod stages.** `BlazeMeter Load
   Test (Perf)` is `type: jenkins`, `master: ems-jenkins` (pipeline JSON
   lines 252-257). We removed Jenkins when choosing GitHub Actions, so on a
   `main` push that stage (and the `aws-perf`/`aws-prod` ECS accounts and
   `ems-perf`/`ems-prod` clusters the perf/prod stages need) won't resolve.
   For a **dev-only learning deploy**, trim the pipeline to stages 1-2 (Deploy
   Dev + Smoke Dev), or remove the `main`-gated stages. Otherwise the execution
   will fail at the first perf/prod stage.
2. **Smoke test URLs are placeholders** (`ems-dev.example.internal`, pipeline
   JSON line 141). Point them at your real ALB DNS (`terraform output alb_url`)
   or the smoke stages fail.
3. **`jules.yml:115`** still has a stale local `base-url` and describes the
   Jenkins path — it's the source of truth for the *Jenkins* alternative, not
   the GitHub Actions path. Harmless, but don't trust that field.
