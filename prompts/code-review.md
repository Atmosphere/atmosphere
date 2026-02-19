# Code review: recent changes

Review the diff of the last `{{N}}` commits for issues that genuinely matter.

## Focus on

- **Bugs**: logic errors, off-by-one, null dereferences, race conditions.
- **Security**: injection, credential leaks, unsafe deserialization.
- **Resource leaks**: unclosed streams, connections, executors.
- **API mistakes**: breaking public API without reason, missing error handling.
- **Consistency**: new code contradicts patterns established in surrounding code.

## Ignore

- Style, formatting, naming preferences.
- Missing Javadoc (unless on a public API).
- TODOs or minor improvements.

## Report format

For each issue:
- **Severity**: CRITICAL / HIGH / MEDIUM
- **File:line**: exact location
- **What**: one-sentence description
- **Fix**: suggested change (code snippet if helpful)

If nothing significant is found, say so. Do not invent issues.
