# CI coverage check

Analyze `.github/workflows/` and `modules/pom.xml` to find modules that
are **built but not tested** in CI.

## Steps

1. List all modules in `modules/pom.xml`.
2. For each module, check if it has test files (`src/test/`).
3. For each CI workflow, check which modules are in the `-pl` test step.
4. Cross-reference: modules with tests but no CI coverage = **GAP**.

## Report format

| Module | Has Tests? | CI Coverage | Status |
|--------|-----------|-------------|--------|
| cpr | ✅ | ✅ | OK |
| ai | ✅ | ❌ | **GAP** |

Suggest the minimal change to the CI workflow to close the gaps.
Do NOT make changes — report only.
