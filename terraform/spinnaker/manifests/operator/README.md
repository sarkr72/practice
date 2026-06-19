# Spinnaker Operator install

Install the OSS Spinnaker Operator (armory fork — the most maintained one).
This applies CRDs, RBAC, and the operator Deployment in the
`spinnaker-operator` namespace.

```bash
kubectl apply -f 00-namespace.yaml

# CRDs + operator manifests, pinned to a known-good release.
# Browse https://github.com/armory/spinnaker-operator/releases for newer.
OPERATOR_VERSION=1.4.0
curl -L "https://github.com/armory/spinnaker-operator/releases/download/v${OPERATOR_VERSION}/manifests.tgz" \
  | tar -xz -C /tmp
kubectl apply -f /tmp/deploy/crds/
kubectl apply -n spinnaker-operator -f /tmp/deploy/operator/kubernetes/
```

Verify the operator is up before applying the SpinnakerService:

```bash
kubectl -n spinnaker-operator rollout status deploy/spinnaker-operator
kubectl -n spinnaker-operator logs deploy/spinnaker-operator -c spinnaker-operator | tail
```
