/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.governance.acs;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Resolves an ACS manifest's {@code extends:} chain, faithful to the upstream
 * resolver in microsoft/agent-governance-toolkit
 * ({@code policy-engine/core/src/manifest.rs}):
 *
 * <ul>
 *   <li><b>Trust root</b> — the canonicalized parent directory of the
 *       top-level manifest, fixed for the entire recursion; nested parents
 *       never re-root it.</li>
 *   <li><b>Confinement</b> — every extends entry resolves relative to the
 *       <em>including</em> file's directory, is canonicalized (symlinks
 *       resolved via {@code toRealPath}) and must stay under the trust root;
 *       absolute entries are allowed but pass the same gate (Correctness
 *       Invariant #4).</li>
 *   <li><b>Order</b> — depth-first, in extends-list order; each parent is
 *       fully resolved (its own extends first) and merged into an
 *       accumulator; the current manifest merges <em>last</em>; the final
 *       result carries no {@code extends}.</li>
 *   <li><b>Strictly additive merge</b> — never child-wins:
 *       {@code agent_control_specification_version} must be identical across
 *       all merged manifests; {@code metadata} deep-merges additively (new
 *       keys inserted, identical values tolerated, differing values error
 *       with the dotted path); {@code policies} / {@code tools} /
 *       {@code annotators} are per-key unions; {@code intervention_points}
 *       merge per point and per field additively, except the {@code policy}
 *       binding which merges atomically. Any conflict throws
 *       {@code "manifest extends conflict for <field> from '<source>':
 *       duplicate definitions must be identical or additive"}, mirroring
 *       upstream's error text.</li>
 *   <li><b>Cycles and depth</b> — a cycle on the stack of canonicalized
 *       paths errors ({@code "manifest extends cycle detected: a -> b ->
 *       a"}); the chain depth is capped at {@link #MAX_EXTENDS_DEPTH},
 *       matching upstream's default limit.</li>
 *   <li><b>Bundle rebasing</b> — a relative {@code bundle:} reference
 *       declared in a base manifest resolves relative to <em>that</em>
 *       file's directory (upstream's {@code resolve_relative_paths}), so
 *       during resolution each file's bundle refs are rewritten to
 *       trust-root-relative paths, which {@code AcsRegoEngine}'s existing
 *       manifest-dir-relative resolution and escape check then handle
 *       unchanged.</li>
 *   <li><b>No URL fetching</b> — Atmosphere never fetches remote manifests.
 *       A non-{@code https} extends URL errors with upstream's scheme
 *       message; an {@code https} URL (string or {@code {url, sha256}}
 *       object form) fails closed with a clear "vendor the manifest
 *       locally" {@link IOException}.</li>
 * </ul>
 *
 * <p>Shape-only parsing of extends-bearing documents (streams, classpath
 * resources — no filesystem directory) stays in
 * {@link AcsManifestParser}; such manifests keep their unresolved chain and
 * {@link AcsManifestPolicy} denies every evaluation fail-closed. This class
 * is invoked by {@code YamlPolicyParser} only when the source names a real
 * file — the parser hands over the root mapping it already parsed via
 * {@link #resolve(String, Path, Map)} so the top-level document is not
 * re-read from disk — and any resolution failure throws {@link IOException}
 * so policy loading fails closed at startup per the {@code PolicyParser}
 * contract.</p>
 */
public final class AcsExtendsResolver {

    /** Depth cap on the extends chain, matching upstream's default limit. */
    public static final int MAX_EXTENDS_DEPTH = 16;

    private static final String EXTENDS_KEY = "extends";
    private static final String METADATA_KEY = "metadata";
    private static final String POINTS_KEY = "intervention_points";
    private static final String POLICIES_KEY = "policies";
    private static final List<String> KEYED_SECTIONS = List.of("tools", "annotators", POLICIES_KEY);

    private final Path trustRoot;
    private final List<Path> stack = new ArrayList<>();

    private AcsExtendsResolver(Path trustRoot) {
        this.trustRoot = trustRoot;
    }

    /**
     * Resolve the manifest at {@code manifestFile} and its full extends
     * chain into a single merged manifest with an empty extends list. The
     * top-level document is read from disk; prefer
     * {@link #resolve(String, Path, Map)} when its root mapping has already
     * been parsed.
     *
     * @param source       diagnostic source id, e.g. {@code yaml:/etc/policies/manifest.yaml}
     * @param manifestFile the top-level manifest file
     * @return the fully resolved manifest
     * @throws IOException on any resolution, confinement, merge-conflict or
     *                     validation failure — fail-closed at startup
     */
    public static AcsManifest resolve(String source, Path manifestFile) throws IOException {
        return resolve(source, manifestFile, null);
    }

    /**
     * Same as {@link #resolve(String, Path)} but reuses {@code root} — the
     * already-parsed YAML root mapping of the <em>top-level</em> document —
     * instead of re-reading {@code manifestFile} from disk; only the parents
     * in the extends chain are loaded from the filesystem.
     * {@code YamlPolicyParser} passes the mapping it just parsed so the
     * stream it consumed is parsed exactly once. The mapping is defensively
     * copied, never mutated.
     *
     * @param source       diagnostic source id
     * @param manifestFile the top-level manifest file — still names the
     *                     confinement trust root
     * @param root         parsed root mapping of {@code manifestFile}, or
     *                     {@code null} to read it from disk
     * @return the fully resolved manifest
     * @throws IOException on any resolution, confinement, merge-conflict or
     *                     validation failure — fail-closed at startup
     */
    public static AcsManifest resolve(String source, Path manifestFile, Map<?, ?> root)
            throws IOException {
        var canonical = canonicalize(manifestFile, null);
        var trustRoot = canonical.getParent();
        if (trustRoot == null) {
            throw new IOException("manifest file '" + canonical + "' has no parent directory");
        }
        var merged = new AcsExtendsResolver(trustRoot)
                .load(canonical, root == null ? null : validatedRoot(canonical, root));
        return AcsManifestParser.parse(source, merged);
    }

    private Map<String, Object> load(Path canonical) throws IOException {
        return load(canonical, null);
    }

    private Map<String, Object> load(Path canonical, Map<String, Object> preParsedRoot)
            throws IOException {
        if (stack.size() + 1 > MAX_EXTENDS_DEPTH) {
            throw new IOException("manifest extends depth exceeds limit " + MAX_EXTENDS_DEPTH
                    + " at '" + canonical + "'");
        }
        var cycleStart = stack.indexOf(canonical);
        if (cycleStart >= 0) {
            var cycle = new StringJoiner(" -> ");
            for (var i = cycleStart; i < stack.size(); i++) {
                cycle.add(stack.get(i).toString());
            }
            cycle.add(canonical.toString());
            throw new IOException("manifest extends cycle detected: " + cycle);
        }

        var root = preParsedRoot == null ? loadYamlRoot(canonical) : preParsedRoot;
        var extendsEntries = extractExtends(root, canonical);
        rebaseBundleRefs(root, canonical.getParent());

        Map<String, Object> resolved = null;
        stack.add(canonical);
        try {
            for (var entry : extendsEntries) {
                var parent = resolveEntry(entry, canonical);
                resolved = mergeManifest(resolved, load(parent), parent.toString());
            }
        } finally {
            stack.remove(stack.size() - 1);
        }
        root.remove(EXTENDS_KEY);
        return mergeManifest(resolved, root, canonical.toString());
    }

    // --- entry resolution -------------------------------------------------

    private Path resolveEntry(Object entry, Path including) throws IOException {
        if (entry instanceof Map<?, ?> obj) {
            // {url:, sha256:} object form — always a URL extends entry.
            throw urlExtends(String.valueOf(obj.get("url")));
        }
        var ref = String.valueOf(entry);
        if (hasUrlScheme(ref)) {
            throw urlExtends(ref.stripLeading());
        }
        var candidate = Path.of(ref);
        var joined = candidate.isAbsolute() ? candidate : including.getParent().resolve(candidate);
        var canonical = canonicalize(joined, including);
        if (!canonical.startsWith(trustRoot)) {
            throw new IOException("extends entry '" + ref + "' in '" + including
                    + "' resolves outside manifest root '" + trustRoot + "': '" + canonical + "'");
        }
        return canonical;
    }

    /**
     * Atmosphere does not fetch remote manifests. A non-https scheme mirrors
     * upstream's rejection; https fails closed with an explicit
     * vendor-it-locally instruction instead of a network fetch.
     */
    private static IOException urlExtends(String url) {
        var colon = url.indexOf(':');
        if (colon <= 0) {
            return new IOException("extends URL '" + url + "' is invalid: missing scheme");
        }
        var scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            return new IOException("extends URL '" + url + "' uses unsupported URL scheme '"
                    + scheme + "'; only https is allowed");
        }
        return new IOException("extends URL '" + url + "': URL extends are not supported by "
                + "this runtime; vendor the manifest locally");
    }

    /** Port of upstream's {@code has_url_scheme}: {@code <alpha><alnum|+|-|.>*://…}. */
    private static boolean hasUrlScheme(String reference) {
        var trimmed = reference.stripLeading();
        if (!trimmed.contains("://")) {
            return false;
        }
        var colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        var first = trimmed.charAt(0);
        if (!(first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z')) {
            return false;
        }
        for (var i = 1; i < colon; i++) {
            var c = trimmed.charAt(i);
            var ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '+' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Path canonicalize(Path path, Path including) throws IOException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            if (including == null) {
                throw new IOException("failed to resolve manifest file '" + path + "': " + e, e);
            }
            throw new IOException("failed to resolve extends file '" + path + "' from '"
                    + including + "': " + e, e);
        }
    }

    // --- per-file loading -------------------------------------------------

    private static Map<String, Object> loadYamlRoot(Path file) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));
        Object document;
        try (var in = Files.newInputStream(file)) {
            document = yaml.load(in);
        } catch (RuntimeException e) {
            throw new IOException("failed to parse YAML from " + file, e);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new IOException("expected a mapping at YAML document root in " + file);
        }
        return validatedRoot(file, root);
    }

    /**
     * Validate the ACS root key and defensively copy — shared by disk loads
     * and the pre-parsed top-level root handed to
     * {@link #resolve(String, Path, Map)}.
     */
    private static Map<String, Object> validatedRoot(Path file, Map<?, ?> root)
            throws IOException {
        var version = root.get(AcsManifestParser.ROOT_KEY);
        if (version == null || String.valueOf(version).isBlank()) {
            throw new IOException(AcsManifestParser.ROOT_KEY
                    + " must be a non-blank string in " + file);
        }
        return stringKeyed(root);
    }

    private static List<?> extractExtends(Map<String, Object> root, Path file) throws IOException {
        var raw = root.get(EXTENDS_KEY);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IOException("'extends' must be a sequence in " + file);
        }
        for (var entry : list) {
            if (entry instanceof Map<?, ?> obj) {
                if (!(obj.get("url") instanceof String url) || url.isBlank()) {
                    throw new IOException("extends entries in '" + file
                            + "' must be non-blank strings or {url, sha256} objects");
                }
            } else if (!(entry instanceof String ref) || ref.isBlank()) {
                throw new IOException("extends entries in '" + file + "' must not be empty");
            }
        }
        return list;
    }

    /**
     * Rewrite each relative {@code bundle:} reference of this file to a
     * trust-root-relative path (upstream {@code resolve_relative_paths},
     * applied per source file). A ref that escapes the trust root — by
     * dot-segments or through a symlink — is left absolute so the engine's
     * confinement check denies it fail-closed.
     *
     * <p>Deliberate narrowing vs upstream: {@code resolve_relative_paths}
     * also rebases adapter {@code data} and annotator
     * {@code system_prompt_file} per file, but {@link AcsManifestParser}
     * drops both fields today, so only {@code bundle} is rebased here. A
     * parser extension that starts carrying either field must extend this
     * rebase step to match.</p>
     */
    private void rebaseBundleRefs(Map<String, Object> root, Path fileDir) {
        if (!(root.get(POLICIES_KEY) instanceof Map<?, ?> policies)) {
            return;
        }
        var rebased = new LinkedHashMap<String, Object>();
        for (var entry : policies.entrySet()) {
            rebased.put(String.valueOf(entry.getKey()), rebasePolicyDecl(entry.getValue(), fileDir));
        }
        root.put(POLICIES_KEY, rebased);
    }

    private Object rebasePolicyDecl(Object decl, Path fileDir) {
        if (!(decl instanceof Map<?, ?> map)) {
            return decl;
        }
        if (!(map.get("bundle") instanceof String bundle) || bundle.isBlank()
                || hasUrlScheme(bundle) || Path.of(bundle).isAbsolute()) {
            return decl;
        }
        var absolute = fileDir.resolve(bundle).normalize();
        var rewritten = confinedToTrustRoot(absolute)
                ? trustRoot.relativize(absolute).toString()
                : absolute.toString();
        if (rewritten.equals(bundle)) {
            return decl;
        }
        var copy = stringKeyed(map);
        copy.put("bundle", rewritten);
        return copy;
    }

    /**
     * Trust-root confinement of a rebased bundle ref. Where the bundle
     * already exists the check is canonical ({@code toRealPath}, matching
     * the extends confinement — the trust root is canonical by
     * construction) so a symlink under the root pointing outside it is
     * caught here; a ref whose target does not exist yet can only be
     * normalize-checked — {@code AcsRegoEngine.resolveBundle} re-applies
     * the canonical check at evaluation time, when the bundle must exist.
     */
    private boolean confinedToTrustRoot(Path absolute) {
        if (!absolute.startsWith(trustRoot)) {
            return false;
        }
        if (!Files.exists(absolute)) {
            return true;
        }
        try {
            return absolute.toRealPath().startsWith(trustRoot);
        } catch (IOException e) {
            return false;
        }
    }

    // --- strictly additive merge (mirrors upstream merge_manifest) --------

    private static Map<String, Object> mergeManifest(Map<String, Object> existing,
                                                     Map<String, Object> incoming,
                                                     String source) throws IOException {
        if (existing == null) {
            return incoming;
        }
        if (!Objects.equals(existing.get(AcsManifestParser.ROOT_KEY),
                incoming.get(AcsManifestParser.ROOT_KEY))) {
            throw conflict(AcsManifestParser.ROOT_KEY, source);
        }
        mergeMetadata(existing, incoming.get(METADATA_KEY), source);
        for (var section : KEYED_SECTIONS) {
            var incomingSection = incoming.get(section);
            if (incomingSection != null) {
                existing.put(section,
                        mergeKeyedValues(section, existing.get(section), incomingSection, source));
            }
        }
        mergeInterventionPoints(existing, incoming.get(POINTS_KEY), source);
        for (var entry : incoming.entrySet()) {
            var key = entry.getKey();
            if (AcsManifestParser.ROOT_KEY.equals(key) || METADATA_KEY.equals(key)
                    || POINTS_KEY.equals(key) || KEYED_SECTIONS.contains(key)) {
                continue;
            }
            var current = existing.get(key);
            if (current == null) {
                existing.put(key, entry.getValue());
            } else if (!Objects.equals(current, entry.getValue())) {
                throw conflict(key, source);
            }
        }
        return existing;
    }

    private static void mergeMetadata(Map<String, Object> existing, Object incomingMeta,
                                      String source) throws IOException {
        if (incomingMeta == null || incomingMeta instanceof Map<?, ?> im && im.isEmpty()) {
            return;
        }
        var existingMeta = existing.get(METADATA_KEY);
        if (existingMeta == null || existingMeta instanceof Map<?, ?> em && em.isEmpty()) {
            existing.put(METADATA_KEY, incomingMeta);
            return;
        }
        if (existingMeta.equals(incomingMeta)) {
            return;
        }
        if (existingMeta instanceof Map<?, ?> em && incomingMeta instanceof Map<?, ?> im) {
            var merged = stringKeyed(em);
            mergeMetadataObject(merged, im, METADATA_KEY, source);
            existing.put(METADATA_KEY, merged);
            return;
        }
        throw conflict(METADATA_KEY, source);
    }

    private static void mergeMetadataObject(Map<String, Object> existing, Map<?, ?> incoming,
                                            String path, String source) throws IOException {
        for (var entry : incoming.entrySet()) {
            var key = String.valueOf(entry.getKey());
            var field = path + "." + key;
            var value = entry.getValue();
            if (!existing.containsKey(key)) {
                existing.put(key, value);
                continue;
            }
            var current = existing.get(key);
            if (Objects.equals(current, value)) {
                continue;
            }
            if (current instanceof Map<?, ?> cm && value instanceof Map<?, ?> vm) {
                var merged = stringKeyed(cm);
                mergeMetadataObject(merged, vm, field, source);
                existing.put(key, merged);
                continue;
            }
            throw conflict(field, source);
        }
    }

    /** Per-key union with atomic values: new key inserted, identical tolerated, different errors. */
    private static Object mergeKeyedValues(String path, Object existingRaw, Object incomingRaw,
                                           String source) throws IOException {
        if (existingRaw == null) {
            return incomingRaw;
        }
        if (existingRaw.equals(incomingRaw)) {
            return existingRaw;
        }
        if (!(existingRaw instanceof Map<?, ?> em) || !(incomingRaw instanceof Map<?, ?> im)) {
            throw conflict(path, source);
        }
        var merged = stringKeyed(em);
        for (var entry : im.entrySet()) {
            var key = String.valueOf(entry.getKey());
            var current = merged.get(key);
            if (current == null) {
                merged.put(key, entry.getValue());
            } else if (!Objects.equals(current, entry.getValue())) {
                throw conflict(path + "." + key, source);
            }
        }
        return merged;
    }

    private static void mergeInterventionPoints(Map<String, Object> existing, Object incomingRaw,
                                                String source) throws IOException {
        if (incomingRaw == null) {
            return;
        }
        var existingRaw = existing.get(POINTS_KEY);
        if (existingRaw == null) {
            existing.put(POINTS_KEY, incomingRaw);
            return;
        }
        if (existingRaw.equals(incomingRaw)) {
            return;
        }
        if (!(existingRaw instanceof Map<?, ?> em) || !(incomingRaw instanceof Map<?, ?> im)) {
            throw conflict(POINTS_KEY, source);
        }
        var merged = stringKeyed(em);
        for (var entry : im.entrySet()) {
            var point = String.valueOf(entry.getKey());
            var current = merged.get(point);
            if (current == null) {
                merged.put(point, entry.getValue());
            } else {
                merged.put(point, mergePointConfig(point, current, entry.getValue(), source));
            }
        }
        existing.put(POINTS_KEY, merged);
    }

    /**
     * Per-field additive merge of one intervention point: scalar fields
     * ({@code policy_target}, {@code policy_target_kind},
     * {@code tool_name_from}, …) fill if absent and error when both set and
     * different; {@code policy} merges <em>atomically</em> — upstream's
     * {@code merge_point_config} takes a non-empty incoming binding only into
     * an empty slot and errors on any non-identical pair, never merging the
     * binding field by field; {@code annotations} is a per-key union.
     */
    private static Object mergePointConfig(String point, Object existingRaw, Object incomingRaw,
                                           String source) throws IOException {
        if (Objects.equals(existingRaw, incomingRaw)) {
            return existingRaw;
        }
        var path = POINTS_KEY + "." + point;
        if (!(existingRaw instanceof Map<?, ?> em) || !(incomingRaw instanceof Map<?, ?> im)) {
            throw conflict(path, source);
        }
        var merged = stringKeyed(em);
        for (var entry : im.entrySet()) {
            var field = String.valueOf(entry.getKey());
            var value = entry.getValue();
            if (isAbsent(value)) {
                continue;
            }
            var current = merged.get(field);
            switch (field) {
                case "annotations" -> merged.put(field,
                        mergeKeyedValues(path + ".annotations", current, value, source));
                case "policy" -> {
                    // Verbatim upstream merge_point_config: a non-empty
                    // incoming binding fills an empty slot or must be
                    // identical to the existing one — a per-field merge
                    // would wrongly let a child graft `query` onto a base's
                    // `{id}` binding, which upstream rejects.
                    if (!isEmptyPolicyBinding(value)) {
                        if (isEmptyPolicyBinding(current)) {
                            merged.put(field, value);
                        } else if (!Objects.equals(current, value)) {
                            throw conflict(path + ".policy", source);
                        }
                    }
                }
                default -> {
                    if (isAbsent(current)) {
                        merged.put(field, value);
                    } else if (!Objects.equals(current, value)) {
                        throw conflict(path + "." + field, source);
                    }
                }
            }
        }
        return merged;
    }

    /**
     * Upstream's binding emptiness test: a {@code policy} binding is empty
     * when it carries no non-blank {@code id} and no {@code query}.
     */
    private static boolean isEmptyPolicyBinding(Object binding) {
        if (binding == null) {
            return true;
        }
        if (!(binding instanceof Map<?, ?> map)) {
            return false;
        }
        var id = map.get("id");
        return (id == null || String.valueOf(id).isBlank()) && map.get("query") == null;
    }

    private static IOException conflict(String field, String source) {
        return new IOException("manifest extends conflict for " + field + " from '" + source
                + "': duplicate definitions must be identical or additive");
    }

    /** Upstream treats a missing key and an empty string alike for point-level scalars. */
    private static boolean isAbsent(Object value) {
        return value == null || value instanceof String s && s.isEmpty();
    }

    private static LinkedHashMap<String, Object> stringKeyed(Map<?, ?> map) {
        var copy = new LinkedHashMap<String, Object>(Math.max(8, map.size() * 2));
        for (var entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }
}
