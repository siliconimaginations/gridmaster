## Summary

<!-- One paragraph: what does this PR do and why? -->

## Type

- [ ] `docs` — design doc or UX doc only
- [ ] `feat` — new feature implementation
- [ ] `fix` — bug fix
- [ ] `test` — tests only
- [ ] `refactor` — no behaviour change
- [ ] `ci` — CI/CD changes
- [ ] `chore` — dependency updates, config

## Checklist

### For `docs` PRs
- [ ] Design doc follows the template in `ENGINEERING_PRINCIPLES.md §2`
- [ ] Open questions are listed

### For implementation PRs
- [ ] Linked design doc: <!-- paste link to merged design doc PR or file -->
- [ ] Implementation matches the design doc (or design doc updated in this PR with reason)
- [ ] Unit tests added / updated
- [ ] Integration tests added where a real PowSyBl call is involved
- [ ] CI passes (build + lint + tests)
- [ ] No `!!` operators without comment; no `any` types without comment
- [ ] No commented-out code
- [ ] Constants named (no magic numbers)

## Testing Notes

<!-- How did you verify this works? What edge cases were considered? -->

## Screenshots / logs (if UI or API change)

<!-- Attach screenshot or paste relevant log output -->

## PR Size

<!-- Count: `git diff --stat origin/main | tail -1` -->
- [ ] Changed lines: _____ (target < 400; hard limit 1 000 — split if over)

## PR Classification (Claude-authored PRs only)

**PR Classification:** <!-- CRITICAL or NON-CRITICAL -->
