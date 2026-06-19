# SpinnakerService install

Templated install. Substitute the placeholders from `terraform output`, then
apply.

## 1. Render the manifest

Run from this directory after `terraform apply` in `terraform/spinnaker`:

```bash
# Pull every value the manifest needs
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-east-1
export SPINNAKER_ROLE_ARN=$(terraform -chdir=../../ output -raw spinnaker_role_arn)
export PERSISTENCE_BUCKET=$(terraform -chdir=../../ output -raw persistence_bucket)

envsubst < spinnakerservice.yaml > /tmp/spinnakerservice.rendered.yaml
```

## 2. Apply

```bash
kubectl apply -f /tmp/spinnakerservice.rendered.yaml
```

## 3. Wait for rollout

Spinnaker takes 5-10 minutes on first install:

```bash
kubectl -n spinnaker get spinnakerservice spinnaker -w
kubectl -n spinnaker get pods
```

When `spin-deck` and `spin-gate` show as `Running`, find the UI:

```bash
kubectl -n spinnaker get svc spin-deck \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Visit `http://<that-hostname>:9000`. No login — see the security warning in
`spinnakerservice.yaml` before exposing this beyond your IP.

## 4. Import the EMS pipeline

```bash
spin pipeline save --file ../../../../spinnaker/pipelines/ems-deploy-cicd.json
```

The pipeline references account names `aws-dev` and `aws-prod` defined in the
SpinnakerService above. Reconcile any name changes before importing.

> **Windows users:** `envsubst` isn't available by default. Use the PowerShell
> rendering steps in [`DEPLOY-WINDOWS.md`](../../../../DEPLOY-WINDOWS.md) instead
> of the bash block above — everything else on this page is the same.
