#!/usr/bin/env python3
"""Check every ExternalSecret in a rendered chart (read from stdin).

Platform contract (secrets addendum): the External Secrets role may read only
<env>/<service account>/... secrets, and the workload is identified by
app.kubernetes.io/name = its service account. Fails when no ExternalSecret is
rendered, so the check cannot pass by finding nothing.

Usage: helm template ... | check-externalsecrets.py <env> <service account>
"""
import sys

import yaml


def main() -> int:
    env, service_account = sys.argv[1], sys.argv[2]
    prefix = f"{env}/{service_account}/"
    errors, found = [], 0
    for doc in yaml.safe_load_all(sys.stdin):
        if not isinstance(doc, dict) or doc.get("kind") != "ExternalSecret":
            continue
        found += 1
        name = doc.get("metadata", {}).get("name")
        label = doc.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/name")
        if label != service_account:
            errors.append(f"ExternalSecret {name}: app.kubernetes.io/name is {label!r}, expected {service_account!r}")
        spec = doc.get("spec", {})
        refs = [d.get("remoteRef", {}) for d in spec.get("data", [])] + \
               [d.get("extract", {}) for d in spec.get("dataFrom", []) if "extract" in d]
        if not refs:
            errors.append(f"ExternalSecret {name}: no remoteRef")
        for ref in refs:
            key = ref.get("key", "")
            if not key.startswith(prefix):
                errors.append(f"ExternalSecret {name}: remoteRef.key {key!r} does not start with {prefix!r}")
    if found == 0:
        errors.append("no ExternalSecret rendered")
    for error in errors:
        print(f"[externalsecret-check] {error}", file=sys.stderr)
    if not errors:
        print(f"[externalsecret-check] {found} ExternalSecret(s) under {prefix}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
