# EMS — documentation index

This project deploys a Spring Boot app (EMS) to AWS ECS Fargate via GitHub
Actions, with a Spinnaker control plane on EKS for learning.

The docs are split into **three layers**, each in its own folder with the
same three files: `INSTRUCTIONS.md` (how to set it up), `DEBUG.md` (what goes
wrong + why), and `README.md` (what every file/section does).

| Layer | Folder | What it covers |
|---|---|---|
| **1. Spinnaker infra** | [`spinnaker-infra/`](spinnaker-infra/) | The EKS cluster, IAM/OIDC, and Spinnaker install. Also creates the GitHub OIDC role the app deploy uses. |
| **2. App infra** | [`app-infra/`](app-infra/) | The EMS platform: ALB, ECS cluster (empty), RDS, ECR, IAM, secrets, subnet tags. |
| **3. App deploy** | [`app-deploy/`](app-deploy/) | The GitHub Actions workflow that builds the image and deploys it to ECS on every push to `main`. |

---

## The big picture

```
                  ┌─────────────────────────────────────────────────┐
   git push main  │  GitHub Actions (.github/workflows/deploy.yml)   │
        ─────────►│  test → Jib → ECR → aws ecs create/update-service│
                  └───────────────┬─────────────────────────────────┘
                                  │ deploys to
                                  ▼
   ┌──────────────────────────── AWS ────────────────────────────┐
   │                                                              │
   │  LAYER 2 (app-infra, terraform/ems):                         │
   │    ALB → ECS cluster "ems-dev" → tasks → RDS MySQL           │
   │    ECR, IAM roles, secrets, subnet tags                      │
   │                                                              │
   │  LAYER 1 (spinnaker-infra, terraform/spinnaker):             │
   │    EKS cluster running Spinnaker (optional, learning)        │
   │    GitHub OIDC trust + the github-actions-ems role           │
   │                                                              │
   └──────────────────────────────────────────────────────────────┘
```

---

## Set-up order (from a clean clone + clean AWS account)

1. **`spinnaker-infra/INSTRUCTIONS.md`**
   - Phase 1: S3 state bucket + DynamoDB lock table (one time).
   - Phase 2: `terraform apply` (creates EKS + the GitHub OIDC role).
   - Phase 3: install Spinnaker. *(Skip Phase 3 if you only want the deploy
     to work and don't care about the Spinnaker UI — Phase 2 still creates
     the OIDC role the app deploy needs.)*
2. **`app-infra/INSTRUCTIONS.md`**
   - Phase 2: `terraform apply -var-file=envs/dev.tfvars` (ALB, ECS, RDS,
     ECR, secrets, subnet tags).
3. **`app-deploy/INSTRUCTIONS.md`**
   - Phase 1: set `AWS_ROLE_ARN` + `AWS_REGION` GitHub variables.
   - Phase 2: `git push origin main` → first deploy.

---

## Which deployer actually runs?

**GitHub Actions → `aws ecs` directly.** That's the real path.

Spinnaker is installed for learning and (optionally) runs a parallel
pipeline that deploys to an isolated stack on the canary target group. It is
**not** what your users hit. Spinnaker's ECS integration has long-standing
bugs (documented in `spinnaker-infra/DEBUG.md`); this repo includes the fix
(subnet `immutable_metadata` tags) but still keeps direct-ECS as the
production path — which is what most teams running Spinnaker on AWS do.

---

## Where the deep-dive lifecycle docs went

Earlier versions of this repo had `docs/REQUEST-LIFECYCLE.md`,
`STARTUP-LIFECYCLE.md`, `DEPLOY-LIFECYCLE.md`, and `SYSTEM-MAP.md`. Their
content is folded into the three-folder structure:
- Request/startup internals → the app's own code is the reference now.
- Deploy lifecycle → `app-deploy/README.md` (workflow architecture section).
- System map (cross-file contracts) → the "Boundary with other folders"
  section in each folder's `README.md`.
