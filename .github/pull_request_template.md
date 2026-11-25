## Summary

Describe the concrete problem, final behavior and affected frontend/backend modules.

## Related Issue (optional)

Link a GitHub issue when there is a separate discussion or requirement record.

## Validation

Describe how the final behavior was checked, including applicable UI/API/schema evidence.
Describe the user flows verified and any API integration still pending.

List executed commands, results and applicable CI run links; do not list unexecuted checks as passing.

```text
make check
make check-commits BASE_REF=origin/main HEAD_REF=HEAD
# Application changes: actual test Compose build, health and same-origin HTTP checks
make check-test-docker
```

- External email/object-storage operation performed: yes / no
- API, database migration, configuration or license effects:

## Documentation Impact

- `Docs-Impact`: updated / none
- Updated documents:
- Reason and named approver when none:
- [ ] Any exemption has the required actual approval under the PR spec.

## Breaking Change

- Breaking change: yes / no
- Maintainer confirmation link when yes:
- Migration and rollback:

## Checklist

- [ ] The change follows the development spec and has evidence for its expected behavior.
- [ ] Title and all commits follow Conventional Commits, DCO and documentation/breaking metadata.
- [ ] Required documentation is updated; artifacts and ignored local files are excluded.
- [ ] Required checks, human reviews and conversations are complete for the current head.
- [ ] Only the maintainer performs the final merge; no agent or bot auto-merge.
