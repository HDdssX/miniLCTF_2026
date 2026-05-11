# Deploy Runbook

1. Confirm the release image tag.
2. Apply manifests in staging.
3. Run smoke checks against the API and worker queue.
4. Promote the release once staging is stable.
5. Record the promoted tag in the change log.

## Smoke checks

- `/healthz` returns `200`
- queue depth remains stable for 5 minutes
- scheduler heartbeat advances
- status relay reports the new build hash
