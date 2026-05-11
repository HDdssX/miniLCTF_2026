# Rollback Runbook

1. Freeze deploy traffic.
2. Scale down workers if the queue is unstable.
3. Restore the previous image tag.
4. Verify the API and worker recovery path.

## Keep in mind

- Rollback first, root-cause later.
- Capture hashes before rotating any pod.
- Keep the recovery shell restrictions in mind when validating a bad node.
