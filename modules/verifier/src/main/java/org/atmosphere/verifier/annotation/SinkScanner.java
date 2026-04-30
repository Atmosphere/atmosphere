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
package org.atmosphere.verifier.annotation;

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.verifier.policy.TaintRule;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reflective derivation of {@link TaintRule}s from {@link Sink}-annotated
 * tool parameters. Lets authors keep the security policy co-located with
 * the code it protects rather than maintaining a parallel YAML file.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * List<TaintRule> rules = SinkScanner.scan(EmailTools.class);
 * Policy policy = new Policy("email-policy",
 *         Set.of("fetch_emails", "send_email"),
 *         rules,
 *         List.of());
 * }</pre>
 *
 * <p>The scanner is purely reflective — no IO, no classpath probing, no
 * runtime-state dependence. It is safe to invoke from configuration code
 * during application startup.</p>
 */
public final class SinkScanner {

    private SinkScanner() {
        // static utility
    }

    /**
     * Scan {@code toolClass} for {@link AiTool}-annotated methods carrying
     * {@link Sink} parameter annotations and return the corresponding
     * {@link TaintRule}s. The scan recurses through super-classes via
     * {@link Class#getMethods()} so inherited tool methods are picked up
     * automatically.
     *
     * @return one rule per (forbidden-source, sink-tool, sink-param) tuple;
     *         never null. Empty when the class declares no sinks.
     */
    public static List<TaintRule> scan(Class<?> toolClass) {
        if (toolClass == null) {
            return List.of();
        }
        var rules = new ArrayList<TaintRule>();
        for (Method method : toolClass.getMethods()) {
            AiTool tool = method.getAnnotation(AiTool.class);
            if (tool == null) {
                continue;
            }
            collectFromMethod(tool.name(), method, rules);
        }
        return rules;
    }

    /**
     * Convenience: scan multiple classes and concatenate the result. Order
     * is preserved.
     */
    public static List<TaintRule> scan(Class<?>... toolClasses) {
        var all = new ArrayList<TaintRule>();
        for (Class<?> c : toolClasses) {
            all.addAll(scan(c));
        }
        return all;
    }

    private static void collectFromMethod(String sinkTool, Method method, List<TaintRule> out) {
        Parameter[] parameters = method.getParameters();
        for (Parameter p : parameters) {
            Sink sink = p.getAnnotation(Sink.class);
            if (sink == null) {
                continue;
            }
            String paramName = parameterName(p);
            for (String source : sink.forbidden()) {
                if (source == null || source.isBlank()) {
                    continue;
                }
                String ruleName = sink.name().isBlank()
                        ? source + "-to-" + sinkTool + "." + paramName
                        : sink.name();
                out.add(new TaintRule(ruleName, source, sinkTool, paramName));
            }
        }
    }

    /**
     * Resolve the wire-format parameter name. Prefers
     * {@link Param#value()} (what the LLM sees and what the planner
     * embeds in workflow JSON); falls back to the reflective parameter
     * name otherwise. Refusing both is a configuration error — taint
     * rules keyed off {@code arg0} are useless.
     */
    private static String parameterName(Parameter p) {
        Param param = p.getAnnotation(Param.class);
        if (param != null && !param.value().isBlank()) {
            return param.value();
        }
        if (p.isNamePresent()) {
            return p.getName();
        }
        throw new IllegalStateException(
                "Cannot resolve parameter name for @Sink-annotated parameter on "
                        + p.getDeclaringExecutable() + " — annotate with @Param or "
                        + "compile with -parameters. (parameter index "
                        + Arrays.asList(p.getDeclaringExecutable().getParameters())
                                .indexOf(p) + ")");
    }
}
