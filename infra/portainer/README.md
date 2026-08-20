# Portainer release contract

This directory documents release integration only. It does not own or mutate the retained manual Stack.

- Keep both GHCR packages private. Portainer uses a read-only package credential stored outside Git and Actions logs.
- Keep the existing Stack webhook URL only in the GitHub `production` environment secret `PORTAINER_WEBHOOK_URL`.
- The retained Stack must select the paired frontend/backend release references together. Never expose backend, PostgreSQL, MinIO API, MinIO Console, Portainer, or the webhook publicly.
- `release.yml` validates one existing annotated `vX.Y.Z` tag twice before building, builds frontend then backend with the same peeled commit revision, validates both returned digests/revisions, checks the tag again, and invokes the webhook last.
- Configure required reviewers for the GitHub `production` environment. Default `dry_run: true` makes no registry or webhook call.

## Operator preflight

1. Record current Stack name, paired image references/digests, health, and webhook configuration with credentials redacted.
2. Confirm at least 8 vCPU, 16 GiB RAM, and 120 GiB free persistent disk at `/srv/narae-signing`; serialize builds.
3. Confirm GHCR packages are private and Portainer can pull both exact `vX.Y.Z` references.
4. Run workflow dry-run first and compare its peeled SHA with `git rev-parse 'vX.Y.Z^{commit}'` from a clean, freshly fetched checkout.
5. Approve non-dry delivery only after both image references are ready for an atomic paired Stack selection. Do not edit or recreate the retained Stack during this task.

## Rollback

Run `scripts/release/validate-rollback.sh CURRENT_TAG ROLLBACK_TAG` from a clean checkout. It accepts only a lower, distinct, existing annotated `vX.Y.Z` whose local and origin object/peeled commit agree. Confirm both private images exist with that same revision before selecting the pair and invoking the protected webhook. Rollback changes images only: no down migration, volume removal, backup operation, or service publication. If the prior application is not compatible with the current schema, stop; do not force rollback.
