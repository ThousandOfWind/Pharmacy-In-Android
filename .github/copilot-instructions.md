# Repository context for GitHub Copilot

This is an offline Android medicine cabinet built with Kotlin, Jetpack Compose, Room, and on-device ML Kit Chinese OCR. Follow the development and delivery workflow in `AGENTS.md`.

## Pull request review

- Review the current PR diff and reachable effects. Report concrete issues with file/line references, severity, and a concise rationale; avoid speculative findings and unrelated scope expansion.
- Prioritize data preservation, explicit Room migrations, reliable save/error behavior, quantity units, and safe OCR candidate adoption. Do not treat an unlabelled date or production date as an expiry date, and do not overwrite manual fields without explicit action.
- Medicine packaging text is recorded information, not medical advice. OCR accuracy must not be overstated.
- Keep tests proportionate: only necessary unit tests, no demand for broad UI/instrumentation suites or extra frameworks by default. Do not remove valuable core rule coverage solely to reduce test count.
- For documentation/instruction-only changes, review consistency, executable commands, permissions, and workflow boundaries. Do not require application tests, APK generation, version bumps, tags, or releases.
- Treat CI, author self-checks, and independent review as distinct evidence. A review of an earlier commit does not cover a newer PR head. Copilot comments are not a substitute for any required human approval.
- Preserve user changes and credentials. Do not recommend force pushes, destructive resets, destructive database fallbacks, credential exposure, or bypassing branch protection.
- Prefer concise review comments in Chinese. State when no concrete issues are found; do not manufacture findings to satisfy a quota.
