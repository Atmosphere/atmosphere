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
package org.atmosphere.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.ServiceLoader;

/**
 * Resolves {@code prompt:} system-prompt references through the
 * {@link PromptRegistry} at the annotation resolution seams
 * ({@code @AiEndpoint} / {@code @Agent} / {@code @Coordinator}).
 *
 * <p>Plain literal values (anything not starting with {@code prompt:}) are
 * returned unchanged, so existing applications see zero behavior change. A
 * managed reference resolves at registration time:</p>
 * <ol>
 *   <li>version selection — pinned {@code @vN} wins; {@code @latest} takes the
 *       highest version; a bare name goes through the deterministic rollout
 *       split when configured ({@link PromptRollout}), else latest. The rollout
 *       unit at the annotation seam is the endpoint identity (path / agent
 *       name), which is stable per deployment; per-user splits call
 *       {@link PromptRegistry#selectVersion(String, String)} with a user id
 *       directly.</li>
 *   <li>templating — {@code {{variable}}} placeholders are substituted from
 *       {@code atmosphere.ai.prompt.var.*} config defaults
 *       ({@link PromptTemplate}); an unresolved placeholder fails closed at
 *       registration.</li>
 * </ol>
 *
 * <p>Unlike the {@code skill:} prefix (which falls back to a default assistant
 * prompt when the remote skills repo is unreachable), a {@code prompt:}
 * reference that cannot be resolved is a deployment error: the registry is
 * local (classpath / disk), so a miss means a broken build, and starting an
 * endpoint with the wrong prompt would silently ship the wrong behavior.
 * Resolution therefore fails closed with {@link IllegalStateException}.</p>
 *
 * <p>The backing registry is discovered once via {@link ServiceLoader} —
 * first provider wins — falling back to the shipped {@link FilePromptRegistry}.</p>
 */
public final class PromptResolver {

    private static final Logger logger = LoggerFactory.getLogger(PromptResolver.class);

    private PromptResolver() {
    }

    /** Lazy holder: registry discovery runs on the first managed resolution. */
    private static final class Holder {
        static final PromptRegistry REGISTRY = discover();

        private static PromptRegistry discover() {
            for (var provided : ServiceLoader.load(PromptRegistry.class)) {
                logger.info("Using PromptRegistry provider {}", provided.getClass().getName());
                return provided;
            }
            return new FilePromptRegistry();
        }
    }

    /**
     * Returns the active registry (ServiceLoader-provided, or the shipped
     * {@link FilePromptRegistry}).
     *
     * @return the registry
     */
    public static PromptRegistry registry() {
        return Holder.REGISTRY;
    }

    /**
     * Returns whether the value is a managed {@code prompt:} reference.
     *
     * @param value the raw annotation value (may be {@code null})
     * @return {@code true} when {@link #resolveSystemPrompt(String, String)}
     *         will consult the registry
     */
    public static boolean isManaged(String value) {
        return PromptReference.isReference(value);
    }

    /**
     * Resolves a system-prompt value: literals pass through untouched; managed
     * references resolve through the registry with config-default template
     * variables.
     *
     * @param value  the raw annotation value (literal, or {@code prompt:...})
     * @param unitId the rollout unit for bare references — the endpoint
     *               identity at the annotation seams
     * @return the resolved system prompt
     * @throws IllegalStateException if a managed reference cannot be resolved,
     *         fails integrity verification, or leaves template variables
     *         unresolved (fail closed)
     */
    public static String resolveSystemPrompt(String value, String unitId) {
        if (!isManaged(value)) {
            return value;
        }
        return resolve(PromptReference.parse(value), unitId, Map.of());
    }

    /**
     * Resolves a parsed reference with explicit per-request template variables
     * (layered over the {@code atmosphere.ai.prompt.var.*} config defaults).
     *
     * @param reference the parsed reference
     * @param unitId    the rollout unit for bare references
     * @param variables per-request template variables; may be empty
     * @return the rendered prompt text
     * @throws IllegalStateException on unknown prompt/version, integrity
     *         failure, or unresolved template variables (fail closed)
     */
    public static String resolve(PromptReference reference, String unitId,
                                 Map<String, String> variables) {
        var registry = registry();
        var name = reference.name();
        String version;
        String selection;
        if (reference.pinnedVersion().isPresent()) {
            version = reference.pinnedVersion().get();
            selection = "pinned";
        } else if (reference.bare() && PromptRollout.configuredWeights(name).isPresent()) {
            version = registry.selectVersion(name, unitId).orElseThrow(
                    () -> missing(registry, name, "rollout"));
            selection = "rollout";
        } else {
            version = registry.latestVersion(name).orElseThrow(
                    () -> missing(registry, name, "latest"));
            selection = "latest";
        }
        var content = registry.content(name, version).orElseThrow(() ->
                new IllegalStateException(
                        "Managed prompt '" + name + "@" + version + "' not found (available versions: "
                                + registry.versions(name) + "). Add prompts/" + name + "/" + version
                                + ".md to the classpath or the " + FilePromptRegistry.PROMPT_DIR_PROPERTY
                                + " directory."));
        var rendered = PromptTemplate.render(content, variables);
        logger.info("Managed prompt '{}' resolved to version '{}' ({}) for unit '{}'",
                name, version, selection, unitId);
        return rendered;
    }

    private static IllegalStateException missing(PromptRegistry registry, String name, String mode) {
        return new IllegalStateException(
                "Managed prompt '" + name + "' has no versions in " + registry.getClass().getSimpleName()
                        + " (" + mode + " selection). Expected prompts/" + name
                        + "/v1.md on the classpath or under the "
                        + FilePromptRegistry.PROMPT_DIR_PROPERTY + " directory.");
    }
}
