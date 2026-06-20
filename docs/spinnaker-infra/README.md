# Spinnaker infra — README

This folder's three files together explain how the Spinnaker control plane is
built, how to set it up, and what to do when it breaks.

| File | Read it when |
|---|---|
| `INSTRUCTIONS.md` | You're standing it up. Step-by-step PowerShell. |
| `DEBUG.md` | Something failed. Every snag we hit during the live build, with the why. |
| `README.md` | You want to *understand* what each file does and how they fit. (You're here.) |

---

## What this folder of the repo is

The **Spinnaker control plane** is the EKS cluster + the IAM trust + the
manifests that install Spinnaker onto that cluster. It is **not** the
application that gets deployed (that's `app-infra` and `app-deploy`).

Two things to know up front:

1. **Spinnaker is optional.** The actual deploy path is `aws ecs` from
   GitHub Actions (see `docs/app-deploy/`). Spinnaker is here for learning —
   it shows how a Netflix-style continuous-delivery tool plugs into AWS.
2. **Spinnaker's ECS support has long-standing bugs.** This setup includes
   the fix (subnet `immutable_metadata` tag in `terraform/ems`) so the
   `SecurityGroupSelector` NPE doesn't bite you. Even with the fix, Spinnaker
   deploys to a separate stack (`ems-spinnaker-vNNN`) so it can never disturb
   the real deploy.

---

## File-by-file: `terraform/spinnaker/`

This Terraform root creates **all the AWS resources Spinnaker needs to run**,
plus the GitHub OIDC trust the app uses.

### Provider + state plumbing

| File | What it does |
|---|---|
| `providers.tf` | Declares the AWS + tls plugins and the **S3 backend** where Terraform's "memory" (state file) lives. The backend block must point at a bucket that already exists — that's the manual bootstrap in `INSTRUCTIONS.md` Phase 1. |
| `variables.tf` | The knobs you can tune: `aws_account_id`, region, EKS cluster name + version, node instance type, S3 bucket name, GitHub repo. Defaults are sensible for a learning setup. |
| `data.tf` | Looks up existing things (the default VPC, your AWS account ID). Doesn't create anything. |
| `outputs.tf` | Prints values you (or other workflows) need: the GitHub Actions role ARN, the Spinnaker IRSA role ARN, the persistence bucket name, the `aws eks update-kubeconfig` command to run. |

### The actual AWS resources

| File | What it creates | Why it matters |
|---|---|---|
| `eks.tf` | **EKS cluster** (`spinnaker`) + 2 worker nodes (`t3.large`) via the upstream `terraform-aws-modules/eks/aws` module + EKS-managed OIDC provider. | Spinnaker microservices (deck, gate, clouddriver, …) all run as pods on this cluster. |
| `iam.tf` | **`spinnaker-managed` IAM role** with: trust for the EKS OIDC provider (IRSA) and a **self-trust** statement (Spinnaker re-assumes itself at deploy time), plus an inline policy with: ECS/ELB managed policies, S3 access to the persistence bucket, `iam:PassRole` to ECS task roles, SSM/Secrets read, ECR pull, and inventory permissions (`Describe*` / `List*`) for clouddriver caching agents. | This is the badge Spinnaker pods present to AWS to do anything. Locked down by both action and resource. |
| `s3.tf` | **S3 bucket** Spinnaker uses for its own data (pipeline definitions, cache snapshots). | Front50 stores everything here. Without it, Spinnaker can't persist your app/pipeline configs. |
| `github_oidc.tf` | **GitHub OIDC provider** + two IAM roles: `github-actions-ems` (app deploy — ECR push, EC2/ELB describe, ECS register/update scoped to `ems-*`, `iam:PassRole` for `ems-*-task*`) and `github-actions-ems-infra` (manual infra workflow — broad admin). Trust scoped to `repo:sarkr72/practice:ref:refs/heads/main`. | This is how GitHub Actions logs into AWS with **zero stored credentials.** Tokens are minted per-run, last 1 hour. |

### Spinnaker install manifests (not Terraform — Kubernetes YAML)

| Path | What it does |
|---|---|
| `manifests/operator/00-namespace.yaml` | Creates the `spinnaker-operator` namespace (the room the controller lives in). |
| `manifests/operator/` (after `tar -xzf`) | The downloaded Spinnaker Operator release — CRDs + the controller Deployment. Use the `cluster/` variant so it can watch the separate `spinnaker` namespace. |
| `manifests/spinnaker/spinnakerservice.yaml` | The "order form" you hand the Operator. Includes substitution placeholders (`__AWS_ACCOUNT_ID__`, `__SPINNAKER_ROLE_ARN__`, `__PERSISTENCE_BUCKET__`, `__AWS_REGION__`) that get filled with Terraform outputs before `kubectl apply`. Configures: persistence (S3), AWS + ECS providers, the webhook inbound trigger, expose-via-NLB, and the IRSA annotation on the service account. |

---

## Workflow architecture — who calls what, when, why

```
1. YOU (on laptop, one time):
   aws s3api create-bucket / put-versioning / put-public-access-block
   aws dynamodb create-table   (terraform-locks)
     → creates the BACKEND that terraform/spinnaker/providers.tf points at.
   Why first: terraform can't store its own memory until the bucket exists.

2. YOU (laptop):
   cd terraform/spinnaker
   terraform init             → reads providers.tf, downloads plugins,
                                connects to the S3 backend.
   terraform apply            → reads ALL *.tf files in this dir, resolves
                                variables (env + account.auto.tfvars), plans,
                                creates: S3 bucket → EKS cluster (~15 min) →
                                OIDC providers → IAM roles.
                                Saves state back to S3.
   Why: the cluster + roles must exist before Spinnaker can be installed.

3. YOU (laptop):
   aws eks update-kubeconfig --name spinnaker --region us-east-1
     → writes the cluster's endpoint + creds into ~/.kube/config.
   Why: kubectl is your remote control. It needs to know where the cluster is.

4. YOU (kubectl):
   kubectl apply -f manifests/operator/
     → installs the Operator (the "robot installer").
   kubectl create namespace spinnaker
     → creates the room Spinnaker itself will live in.
   kubectl apply -f spinnakerservice.rendered.yaml
     → submits the order form.
   Why: the Operator can't build Spinnaker until you give it spec.

5. OPERATOR (running in the cluster, reacts to the SpinnakerService):
   reads spec → creates Deployments for deck, gate, clouddriver, orca,
                front50, echo, rosco → creates NLBs for deck + gate.
   Why: separation of concerns. You declare what; it reconciles how.

6. CLOUDDRIVER POD (every minute):
   Uses IRSA to assume the spinnaker-managed role → calls
   ecs:ListClusters, ec2:DescribeSecurityGroups, secretsmanager:ListSecrets,
   etc. → populates caches. The EcsSecurityGroupCachingAgent caches by
   account/region/vpcId — this is the cache that needs the subnet
   immutable_metadata tag (in terraform/ems) to find the VPC.
   Why: every deploy needs current AWS inventory. Out-of-date caches = NPEs.
```

---

## The boundary with other folders

```
terraform/spinnaker/  (this folder)
   creates the Spinnaker control plane + GitHub OIDC trust.
   ▲
   │ outputs the role ARNs and S3 bucket name as terraform outputs.
   ▼
terraform/ems/        (docs/app-infra)
   creates the EMS workload's AWS resources.
   The "spinnaker_subnet_tags.tf" file there tags subnets with
   immutable_metadata, which lets Spinnaker resolve subnetType.
   ▲
   │ creates IAM roles named "ems-*-task*", security group
   │ "ems-dev-tasks", target groups, etc., all referenced by name
   │ in the Spinnaker pipeline JSON.
   ▼
.github/workflows/deploy.yml  (docs/app-deploy)
   does the real deploy via aws ecs. Optionally calls Spinnaker's
   webhook for parallel learning execution.
```

The contract between these layers is **string names**: pipeline JSON
references `ems-dev-tasks` (SG name), `ems-dev-stable` (target group name),
`ems-*-task*` (IAM role pattern). Rename one, update them all.

---

## Cost reality check

With everything running:
- EKS cluster + 2× t3.large: ~$5/day
- 2× NLB for spin-deck and spin-gate: ~$1/day
- (plus app-infra costs)

Tear it all down when you stop for the day — `INSTRUCTIONS.md` has the
teardown commands.
