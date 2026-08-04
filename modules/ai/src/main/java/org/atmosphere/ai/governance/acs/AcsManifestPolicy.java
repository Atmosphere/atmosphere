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

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * {@link GovernancePolicy} adapter for an ACS manifest: evaluates the
 * manifest's intervention-point bindings at the seams Atmosphere's
 * governance plane actually reaches, and maps ACS verdicts onto
 * {@link PolicyDecision}s.
 *
 * <p><b>Intervention-point coverage.</b> ACS is host-driven: the host
 * evaluates the points it implements. Atmosphere's policy chain runs at
 * three seams, mapped as follows:</p>
 * <ul>
 *   <li>{@code input} — {@link PolicyContext.Phase#PRE_ADMISSION} on the
 *       user turn (no {@code tool_name} metadata)</li>
 *   <li>{@code pre_tool_call} — {@code PRE_ADMISSION} on the synthetic
 *       tool-call intent built by {@code PolicyAdmissionGate.admitToolCall}
 *       ({@code tool_name} metadata present)</li>
 *   <li>{@code output} — {@link PolicyContext.Phase#POST_RESPONSE} on the
 *       accumulated response</li>
 * </ul>
 * <p>Points with no Atmosphere seam ({@code pre_model_call},
 * {@code post_model_call}, {@code post_tool_call}, {@code agent_startup},
 * {@code agent_shutdown}) are not evaluated — that is host scope, not
 * fail-open: a point the host never reaches is never presented to ACS.</p>
 *
 * <p><b>Fail-closed semantics</b> (Correctness Invariant #6, matching the
 * upstream ACS safety model): an unresolved {@code extends:} chain (file
 * -sourced chains are resolved by {@link AcsExtendsResolver} at parse time;
 * stream- or classpath-sourced manifests cannot be), a bound policy type
 * with no registered {@link AcsPolicyEngine}, an engine throw, or a
 * transform verdict whose target cannot be applied all produce
 * {@link PolicyDecision.Deny} with an {@code acs_runtime_error} reason —
 * never an admit.</p>
 *
 * <p><b>Transform verdicts.</b> A {@code transform} verdict on the
 * {@code input} point whose path targets the whole policy target
 * ({@code $policy_target}) with a string value becomes
 * {@link PolicyDecision.Transform} carrying the rewritten message. Any
 * other transform target is denied fail-closed as an invalid transform
 * target.</p>
 */
public final class AcsManifestPolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(AcsManifestPolicy.class);

    /** Lazily-loaded ServiceLoader engines, keyed by {@link AcsPolicyEngine#type()}. */
    private static final class Engines {
        private static final Map<String, AcsPolicyEngine> LOADED = load();

        private static Map<String, AcsPolicyEngine> load() {
            var engines = new HashMap<String, AcsPolicyEngine>();
            for (var engine : ServiceLoader.load(AcsPolicyEngine.class)) {
                var previous = engines.putIfAbsent(engine.type(), engine);
                if (previous != null) {
                    logger.warn("Duplicate AcsPolicyEngine for type '{}': keeping {}, ignoring {}",
                            engine.type(), previous.getClass().getName(),
                            engine.getClass().getName());
                }
            }
            return Map.copyOf(engines);
        }
    }

    private final AcsManifest manifest;
    private final String source;
    private final Path manifestDir;
    private final Function<String, AcsPolicyEngine> engineLookup;

    /**
     * @param manifest    the parsed manifest
     * @param source      diagnostic source id from the parser
     * @param manifestDir directory containing the manifest file, or
     *                    {@code null} when parsed from a non-filesystem
     *                    source (engines then deny relative bundles)
     */
    public AcsManifestPolicy(AcsManifest manifest, String source, Path manifestDir) {
        this(manifest, source, manifestDir, type -> Engines.LOADED.get(type));
    }

    AcsManifestPolicy(AcsManifest manifest, String source, Path manifestDir,
                      Function<String, AcsPolicyEngine> engineLookup) {
        this.manifest = manifest;
        this.source = source == null ? "" : source;
        this.manifestDir = manifestDir;
        this.engineLookup = engineLookup;
        if (!manifest.extendsRefs().isEmpty()) {
            logger.warn("ACS manifest '{}' ({}) declares an extends chain {} that was not "
                            + "resolved — chains resolve only when the manifest is loaded from a "
                            + "filesystem path (see AcsExtendsResolver); every evaluation will "
                            + "deny fail-closed until then",
                    manifest.name(), this.source, manifest.extendsRefs());
        }
    }

    /**
     * Derive the manifest's directory from a parser source id like
     * {@code yaml:/etc/atmosphere/policies/manifest.yaml}. Returns
     * {@code null} when the source does not name an existing file.
     */
    public static Path resolveManifestDir(String source) {
        var file = resolveManifestFile(source);
        return file == null ? null : file.getParent();
    }

    /**
     * Derive the manifest's file path from a parser source id like
     * {@code yaml:/etc/atmosphere/policies/manifest.yaml}. Returns
     * {@code null} when the source does not name an existing file.
     */
    public static Path resolveManifestFile(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        var colon = source.indexOf(':');
        var candidate = colon >= 0 ? source.substring(colon + 1) : source;
        if (candidate.isBlank()) {
            return null;
        }
        try {
            var path = Path.of(candidate).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            logger.trace("source id {} is not a filesystem path", source, e);
            return null;
        }
    }

    /** The parsed manifest — exposed for conformance assertions and tooling. */
    public AcsManifest manifest() {
        return manifest;
    }

    @Override
    public String name() {
        return manifest.name();
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public String version() {
        return manifest.version();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var pointName = interventionPointFor(context);
        var point = manifest.interventionPoints().get(pointName);
        if (point == null) {
            return PolicyDecision.admit();
        }
        if (!manifest.extendsRefs().isEmpty()) {
            return PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                    + ": manifest '" + manifest.name() + "' has an unresolved extends chain "
                    + manifest.extendsRefs());
        }
        var policy = manifest.policies().get(point.policyId());
        if (policy == null) {
            // Parser rejects unknown ids; guard stays for hand-built manifests.
            return PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                    + ": unknown policy id '" + point.policyId() + "'");
        }
        var engine = engineLookup.apply(policy.type());
        if (engine == null) {
            return PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                    + ": no AcsPolicyEngine registered for type '" + policy.type()
                    + "' (policy '" + point.policyId() + "')");
        }

        var evaluation = new AcsPolicyEngine.Evaluation(point.policyId(), policy,
                resolveQuery(point, policy), manifestDir, pointName,
                buildInput(pointName, point, context));
        AcsPolicyEngine.Verdict verdict;
        try {
            verdict = engine.evaluate(evaluation);
        } catch (RuntimeException e) {
            logger.warn("AcsPolicyEngine {} threw on {} of manifest '{}' — denying fail-closed",
                    engine.getClass().getName(), pointName, manifest.name(), e);
            return PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                    + ": engine threw: " + e.getMessage());
        }
        if (verdict == null) {
            return PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                    + ": engine returned null verdict");
        }
        return mapVerdict(verdict, pointName, context);
    }

    private static String interventionPointFor(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return "output";
        }
        var request = context.request();
        var metadata = request == null ? null : request.metadata();
        return metadata != null && metadata.get("tool_name") != null
                ? "pre_tool_call" : "input";
    }

    private String resolveQuery(AcsManifest.InterventionPoint point,
                                AcsManifest.PolicyRef policy) {
        if (point.query() != null && !point.query().isBlank()) {
            return point.query();
        }
        if (policy.query() != null && !policy.query().isBlank()) {
            return policy.query();
        }
        return "data.agent_control_specification." + point.policyId() + ".verdict";
    }

    /**
     * Build the canonical ACS policy input: {@code intervention_point},
     * {@code policy_target} (path, kind, value) and, on tool points,
     * {@code tool}. Extra snapshot fields are permitted by the spec and
     * carry Atmosphere's request identity.
     */
    private Map<String, Object> buildInput(String pointName,
                                           AcsManifest.InterventionPoint point,
                                           PolicyContext context) {
        var input = new LinkedHashMap<String, Object>();
        input.put("intervention_point", pointName);

        var target = new LinkedHashMap<String, Object>();
        if (point.policyTarget() != null) {
            target.put("path", point.policyTarget());
        }
        if (point.policyTargetKind() != null) {
            target.put("kind", point.policyTargetKind());
        }
        target.put("value", targetValue(pointName, context));
        input.put("policy_target", target);

        var request = context.request();
        if ("pre_tool_call".equals(pointName) && request != null && request.metadata() != null) {
            var toolName = String.valueOf(request.metadata().get("tool_name"));
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", toolName);
            var declared = manifest.tools().get(toolName);
            if (declared != null) {
                if (declared.id() != null) {
                    tool.put("id", declared.id());
                }
                if (!declared.clearance().isEmpty()) {
                    tool.put("clearance", declared.clearance());
                }
                if (!declared.securityLabels().isEmpty()) {
                    tool.put("security_labels", declared.securityLabels());
                }
            }
            input.put("tool", tool);
        }

        if (request != null) {
            var snapshot = new LinkedHashMap<String, Object>();
            if (request.userId() != null) {
                snapshot.put("user_id", request.userId());
            }
            if (request.sessionId() != null) {
                snapshot.put("session_id", request.sessionId());
            }
            if (request.agentId() != null) {
                snapshot.put("agent_id", request.agentId());
            }
            if (request.conversationId() != null) {
                snapshot.put("conversation_id", request.conversationId());
            }
            if (!snapshot.isEmpty()) {
                input.put("snapshot", snapshot);
            }
        }
        return input;
    }

    private static Object targetValue(String pointName, PolicyContext context) {
        var request = context.request();
        return switch (pointName) {
            case "output" -> context.accumulatedResponse();
            case "pre_tool_call" -> {
                var metadata = request == null ? null : request.metadata();
                var args = metadata == null ? null : metadata.get("tool_args");
                yield args != null ? args
                        : (metadata == null ? Map.of() : metadata.getOrDefault("tool_args_preview", Map.of()));
            }
            default -> request == null ? "" : request.message();
        };
    }

    private PolicyDecision mapVerdict(AcsPolicyEngine.Verdict verdict,
                                      String pointName, PolicyContext context) {
        return switch (verdict.decision()) {
            case ALLOW -> PolicyDecision.admit();
            case DENY -> PolicyDecision.deny(denyReason(verdict));
            case TRANSFORM -> {
                if ("input".equals(pointName)
                        && "$policy_target".equals(verdict.transformPath())
                        && verdict.transformValue() instanceof String replacement
                        && context.request() != null) {
                    yield PolicyDecision.transform(context.request().withMessage(replacement));
                }
                yield PolicyDecision.deny(AcsPolicyEngine.RUNTIME_ERROR
                        + ": invalid transform target '" + verdict.transformPath()
                        + "' at " + pointName);
            }
        };
    }

    private static String denyReason(AcsPolicyEngine.Verdict verdict) {
        if (!verdict.reason().isBlank()) {
            return verdict.reason();
        }
        return verdict.message().isBlank() ? "acs policy denied" : verdict.message();
    }
}
