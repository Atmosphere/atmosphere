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
package org.atmosphere.a2a.registry;

import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scans objects for methods annotated with {@link AgentSkill} and {@link AgentSkillHandler},
 * registers them as executable skill entries, and builds an {@link AgentCard} from the
 * collected skill metadata. Also supports programmatic registration for cross-protocol bridges.
 */
public final class A2aRegistry {

    private static final Logger logger = LoggerFactory.getLogger(A2aRegistry.class);

    /** A registered skill entry binding metadata, the reflective method handle, and its parameters. */
    public record SkillEntry(String id, String name, String description, List<String> tags,
                             Method method, Object instance, List<ParamEntry> params) {
    }

    /** Metadata for a single parameter of a skill handler method. */
    public record ParamEntry(String name, String description, boolean required, Class<?> type) {
    }

    private final Map<String, SkillEntry> skills = new LinkedHashMap<>();

    public void scan(Object instance) {
        for (var method : instance.getClass().getDeclaredMethods()) {
            var skillAnn = method.getAnnotation(AgentSkill.class);
            var handlerAnn = method.getAnnotation(AgentSkillHandler.class);
            if (skillAnn != null && handlerAnn != null) {
                var params = extractParams(method);
                var entry = new SkillEntry(skillAnn.id(), skillAnn.name(),
                        skillAnn.description(), List.of(skillAnn.tags()),
                        method, instance, params);
                skills.put(skillAnn.id(), entry);
                logger.debug("Registered A2A skill: {} ({})", skillAnn.name(), skillAnn.id());
            }
        }
    }

    public Map<String, SkillEntry> skills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<SkillEntry> skill(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    /**
     * Programmatically register a skill without annotation scanning. This allows
     * cross-protocol bridges (e.g. the Agent processor) to register executable
     * skill handlers that back the Agent Card skills.
     *
     * @param id          unique skill identifier
     * @param name        human-readable skill name
     * @param description skill description
     * @param tags        classification tags
     * @param method      the method to invoke when the skill is executed
     * @param instance    the object instance to invoke the method on
     * @param params      parameter metadata for the skill
     */
    public void registerSkill(String id, String name, String description, List<String> tags,
                              Method method, Object instance, List<ParamEntry> params) {
        var entry = new SkillEntry(id, name, description, tags, method, instance, params);
        skills.put(id, entry);
        logger.debug("Programmatically registered A2A skill: {} ({})", name, id);
    }

    public AgentCard buildAgentCard(String name, String description, String version, String url) {
        return buildAgentCard(name, description, version, url, null);
    }

    public AgentCard buildAgentCard(String name, String description, String version,
                                    String url, List<String> guardrails) {
        var skillList = skills.values().stream()
                .map(s -> new Skill(s.id(), s.name(), s.description(), s.tags(), Map.of(), Map.of()))
                .toList();
        var capabilities = new AgentCard.AgentCapabilities(true, false, true);
        return new AgentCard(name, description, url, version, null, null,
                capabilities, skillList, Map.of(), List.of("text"), List.of("text"),
                guardrails);
    }

    private List<ParamEntry> extractParams(Method method) {
        var params = new ArrayList<ParamEntry>();
        for (var param : method.getParameters()) {
            var ann = param.getAnnotation(AgentSkillParam.class);
            // Skip TaskContext (injectable)
            if (param.getType() == TaskContext.class) {
                continue;
            }
            if (ann != null) {
                params.add(new ParamEntry(ann.name(), ann.description(), ann.required(), param.getType()));
            }
        }
        return List.copyOf(params);
    }
}
