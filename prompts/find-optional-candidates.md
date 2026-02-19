# Find null-returning methods → Optional candidates

Analyze `{{FILE}}` (or directory) for public methods that return `null`
instead of `Optional`.

## What to look for

- Methods named `find*`, `get*`, `lookup*` that return object types.
- Methods that explicitly `return null` in some branches.
- Methods documented as "returns null if not found".

## Report format

For each candidate, report:

1. Method signature and file path.
2. Whether it's defined in an **interface** (changing to Optional = breaking API change).
3. How many callers exist in the codebase (grep for usages).
4. **Verdict**: SAFE TO CHANGE / BREAKING CHANGE.

## Do NOT make changes

This is an analysis-only prompt. Report findings but do not edit files.
