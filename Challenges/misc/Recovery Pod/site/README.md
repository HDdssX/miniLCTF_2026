# ops-playbooks

Internal runbooks for routine deploy and rollback tasks.

## Scope

- api
- worker
- scheduler
- status relay

## Ground rules

- Keep procedures short enough to follow from a restricted pod.
- Prefer reproducible checks over tribal knowledge.
- Record hashes before replacing a broken container.

## Helper scripts

- `scripts/check_services.sh`
- `scripts/recover_snapshot.py`
