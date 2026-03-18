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

import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.a2a.types.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class A2aRegistry {

    private static final Logger logger = LoggerFactory.getLogger(A2aRegistry.class);

    public record SkillEntry(String id, String name, String description, List<String> tags,
                             Method method, Object instance, List<ParamEntry> params) {
    }

    public record ParamEntry(String name, String description, boolean required, Class<?> type) {
    }

    private final Map<String, SkillEntry> skills = new ConcurrentHashMap<>();

    public void scan(Object instance) {
        for (var method : instance.getClass().getDeclaredMethods()) {
            var skillAnn = method.getAnnotation(A2aSkill.class);
            var handlerAnn = method.getAnnotation(A2aTaskHandler.class);
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

    public AgentCard buildAgentCard(String name, String description, String version, String url) {
        var skillList = skills.values().stream()
                .map(s -> new Skill(s.id(), s.name(), s.description(), s.tags(), Map.of(), Map.of()))
                .toList();
        var capabilities = new AgentCard.AgentCapabilities(true, false, true);
        return new AgentCard(name, description, url, version, null, null,
                capabilities, skillList, Map.of(), List.of("text"), List.of("text"));
    }

    private List<ParamEntry> extractParams(Method method) {
        var params = new ArrayList<ParamEntry>();
        for (var param : method.getParameters()) {
            var ann = param.getAnnotation(A2aParam.class);
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
