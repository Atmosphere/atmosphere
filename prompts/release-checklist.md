# Release checklist

Run this skill **before triggering the release workflow** to align all
version references across the project.

## Inputs

- `{{VERSION}}` — the release version (e.g., `4.0.3`)
- `{{NEXT_DEV}}` — the next SNAPSHOT version (e.g., `4.0.4-SNAPSHOT`)
- `{{JS_VERSION}}` — atmosphere.js version if changing (e.g., `5.0.2`), or `unchanged`

## Steps

### 1. Verify prerequisites

- [ ] All CI workflows on `main` are green
- [ ] `./mvnw install` passes locally
- [ ] `cd atmosphere.js && npm test && npm run build` passes

### 2. Update Java version references

Find and replace the **previous release version** with `{{VERSION}}` in:

| File | What to update |
|------|---------------|
| `README.md` | All `<version>` tags and Gradle coordinates |
| `modules/ai/README.md` | `<version>` in Maven snippet |
| `modules/mcp/README.md` | `<version>` in Maven snippet |
| `modules/cpr/README.md` | `<version>` in Maven snippet |
| `modules/spring-boot-starter/README.md` | `<version>` in Maven snippet |
| `modules/quarkus-extension/README.md` | `<version>` in Maven snippet |

**Verification**: `grep -rn 'OLD_VERSION' --include='*.md' | grep -v CHANGELOG | grep -v MIGRATION` should return nothing.

### 3. Update atmosphere.js version (if `{{JS_VERSION}}` is not `unchanged`)

| File | What to update |
|------|---------------|
| `atmosphere.js/package.json` | `"version": "{{JS_VERSION}}"` |
| `atmosphere.js/src/version.ts` | `export const VERSION = '{{JS_VERSION}}'` |

**Verification**: The version in `package.json` and `version.ts` must match exactly.

### 4. Cross-reference documentation against code

For each sample and module README, verify:

- **Class names** mentioned in README exist in the corresponding `src/` directory
- **File paths** in project structure trees match actual files on disk
- **Configuration properties** match the actual `@ConfigProperty` or `@Value` annotations
- **Import paths** in code examples are valid
- **Annotation names** match actual Java annotations
- **npm script names** in atmosphere.js README match `package.json` scripts

Run these automated checks:

```bash
# Check for stale JavaScript file references (should use assets/)
grep -rn 'javascript/' samples/*/README.md

# Check atmosphere.js version sync
PKG=$(grep '"version"' atmosphere.js/package.json | grep -o '[0-9.]*')
SRC=$(grep 'VERSION' atmosphere.js/src/version.ts | grep -o '[0-9.]*')
[ "$PKG" = "$SRC" ] && echo "✅ JS versions match: $PKG" || echo "❌ MISMATCH: package.json=$PKG version.ts=$SRC"

# Check npm script names referenced in README
for cmd in $(grep -oP 'npm run \K[\w-]+' atmosphere.js/README.md | sort -u); do
  grep -q "\"$cmd\"" atmosphere.js/package.json && echo "✅ $cmd" || echo "❌ $cmd not in package.json"
done

# Check connection states match TypeScript source
grep -o "'[a-z]*'" atmosphere.js/src/types/index.ts | sort
```

### 5. Update CHANGELOG.md

Add a section for `{{VERSION}}` at the top with:
- New features
- Bug fixes
- Breaking changes (if any)
- Dependency updates

Use `git log --oneline PREV_TAG..HEAD` to generate the list.

### 6. Commit and tag

```bash
git add -A
git commit -m "release: prepare {{VERSION}}"
git push origin main
```

Then trigger the `Release Atmosphere 4.x` workflow with:
- Release version: `{{VERSION}}`
- Next dev version: `{{NEXT_DEV}}`
- JS version: `{{JS_VERSION}}`

### 7. Post-release

After the workflow completes:

- [ ] Verify Maven Central has the new artifacts
- [ ] Verify npm has the new atmosphere.js (if published)
- [ ] Update the website (gh-pages) if version numbers are displayed
- [ ] Create a GitHub Release with the CHANGELOG entry

## Common mistakes this checklist prevents

1. **README versions lag behind releases** — every README has hardcoded Maven coordinates
2. **version.ts diverges from package.json** — two places to update for JS version
3. **Sample READMEs reference deleted files** — project structure trees go stale
4. **npm script names change but README doesn't** — e.g., `type-check` vs `typecheck`
5. **Connection states added in code but not documented** — TypeScript types vs README
