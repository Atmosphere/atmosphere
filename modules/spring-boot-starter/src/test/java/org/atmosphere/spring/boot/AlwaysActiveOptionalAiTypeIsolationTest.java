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
package org.atmosphere.spring.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;

/**
 * Generic guard against the {@code NoClassDefFoundError: org/atmosphere/ai/...}
 * startup crash on samples that lack the optional {@code atmosphere-ai}
 * dependency (the starter declares {@code atmosphere-ai} {@code <optional>true},
 * so it does not reach a consumer's runtime classpath).
 *
 * <p><strong>The trap.</strong> Spring reflects over every method of an
 * always-active bean at registration; {@link Class#getDeclaredMethods()}
 * force-loads each method's parameter and return types. If an
 * {@code org.atmosphere.ai.*} type appears in one of those signatures, the JVM
 * throws {@link NoClassDefFoundError} at startup wherever the AI package is
 * absent. The optional read must live in a classpath-guarded helper (e.g.
 * {@link TapeAdminSupport}), never in a controller signature. This class of bug
 * has bitten twice — first in the A2A module, then when the console Tape tab
 * put tape types on {@link AtmosphereConsoleInfoEndpoint} /
 * {@link AtmosphereAdminEndpoint} (fixed by isolating them into
 * {@link TapeAdminSupport}).
 *
 * <p><strong>What this asserts.</strong> Every always-active {@code @Controller}
 * surface the starter contributes (discovered from the autoconfigure imports
 * registry, so a NEW controller is picked up automatically) is force-loaded the
 * same way Spring does and asserted to keep {@code org.atmosphere.ai.*} out of
 * its signatures. Controllers that genuinely cannot register without
 * {@code atmosphere-ai} are exempt via {@link #AI_GATED} — the ai-free
 * {@code spring-boot-durable-sessions} boot smoke is the dynamic backstop that
 * would catch any abuse of that allowlist.
 */
class AlwaysActiveOptionalAiTypeIsolationTest {

    private static final String AI_PACKAGE = "org.atmosphere.ai.";
    private static final String STARTER_PACKAGE = "org.atmosphere.spring.boot.";
    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * Starter controllers proven NOT to register unless {@code atmosphere-ai} is
     * on the classpath, hence permitted AI types in their signatures. Each entry
     * is justified below. A controller that is actually always-active must NOT be
     * added here; {@link #aiGatedAllowlistEntriesAreGenuinelyAiGated()} enforces
     * that every entry really carries AI gating.
     */
    private static final Set<String> AI_GATED = Set.of(
            // @ConditionalOnClass(HarnessPreset.class) — an org.atmosphere.ai.preset type.
            "org.atmosphere.spring.boot.AtmosphereAgentWorkspaceEndpoint",
            // @ConditionalOnBean(InteractionService) — that bean is produced by
            // InteractionsAutoConfiguration, itself @ConditionalOnClass(AiConfig.class),
            // so the endpoint is transitively ai-gated.
            "org.atmosphere.spring.boot.InteractionsEndpoint");

    @Test
    void noAlwaysActiveStarterControllerLeaksOptionalAiTypeInSignatures() throws IOException {
        List<Class<?>> controllers = discoverStarterControllers();
        assertFalse(controllers.isEmpty(),
                "expected to discover starter @Controller surfaces from " + IMPORTS_RESOURCE);

        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : controllers) {
            if (AI_GATED.contains(c.getName())) {
                continue;
            }
            scanned.add(c.getSimpleName());
            offenders.addAll(aiTypesInSignatures(c));
        }
        assertFalse(scanned.isEmpty(),
                "no always-active controllers were scanned — discovery or the allowlist is wrong");
        assertTrue(offenders.isEmpty(),
                "always-active starter controllers must keep optional " + AI_PACKAGE
                        + "* types out of method signatures (Spring force-loads them via "
                        + "getDeclaredMethods at startup, crashing samples without atmosphere-ai; "
                        + "move the read into a classpath-guarded helper like TapeAdminSupport). "
                        + "scanned=" + scanned + " offenders=" + offenders);
    }

    @Test
    void aiGatedAllowlistHasNoStaleEntries() throws IOException {
        Set<String> discovered = new LinkedHashSet<>();
        for (Class<?> c : discoverStarterControllers()) {
            discovered.add(c.getName());
        }
        for (String allow : AI_GATED) {
            assertTrue(discovered.contains(allow),
                    "AI_GATED allowlist entry " + allow + " is no longer a discovered starter controller "
                            + "(renamed/removed?) — update the allowlist so the guard stays honest");
        }
    }

    /**
     * Each allowlisted controller must genuinely carry AI gating, so the allowlist
     * cannot be abused to silence the scan for an actually-always-active class.
     */
    @Test
    void aiGatedAllowlistEntriesAreGenuinelyAiGated() throws Exception {
        // Direct: @ConditionalOnClass on an org.atmosphere.ai.* type.
        Class<?> workspace = Class.forName("org.atmosphere.spring.boot.AtmosphereAgentWorkspaceEndpoint");
        assertTrue(referencesAiType(workspace.getAnnotation(ConditionalOnClass.class)),
                "AtmosphereAgentWorkspaceEndpoint must stay @ConditionalOnClass on an " + AI_PACKAGE + "* type");

        // Transitive: its gating bean (InteractionService) is produced by an ai-@ConditionalOnClass config.
        Class<?> interAuto = Class.forName("org.atmosphere.spring.boot.InteractionsAutoConfiguration");
        assertTrue(referencesAiType(interAuto.getAnnotation(ConditionalOnClass.class)),
                "InteractionsAutoConfiguration must stay @ConditionalOnClass on an " + AI_PACKAGE
                        + "* type — it gates the InteractionService bean that InteractionsEndpoint needs");
    }

    private static boolean referencesAiType(ConditionalOnClass onClass) {
        if (onClass == null) {
            return false;
        }
        // value() force-loads the referenced classes (present in the ai-scoped test classpath);
        // name() carries the string form used when the class may be absent.
        for (Class<?> t : onClass.value()) {
            if (t.getName().startsWith(AI_PACKAGE)) {
                return true;
            }
        }
        for (String n : onClass.name()) {
            if (n.startsWith(AI_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> aiTypesInSignatures(Class<?> controller) {
        List<String> offenders = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getReturnType().getName().startsWith(AI_PACKAGE)) {
                offenders.add(controller.getSimpleName() + "#" + m.getName()
                        + " returns " + m.getReturnType().getName());
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (p.getName().startsWith(AI_PACKAGE)) {
                    offenders.add(controller.getSimpleName() + "#" + m.getName() + " takes " + p.getName());
                }
            }
        }
        return offenders;
    }

    /**
     * The starter's controllers are all registered as {@code @AutoConfiguration}
     * entries, so the autoconfigure imports file is their authoritative registry.
     * Reading it — rather than a condition-evaluating component scan, which would
     * drop {@code @ConditionalOnWebApplication} controllers when no web context is
     * present — is deterministic and picks up any new controller automatically.
     */
    private static List<Class<?>> discoverStarterControllers() throws IOException {
        Set<String> classNames = new LinkedHashSet<>();
        Enumeration<URL> resources =
                Thread.currentThread().getContextClassLoader().getResources(IMPORTS_RESOURCE);
        while (resources.hasMoreElements()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    if (s.startsWith(STARTER_PACKAGE) && !s.startsWith("#")) {
                        classNames.add(s);
                    }
                }
            }
        }
        List<Class<?>> controllers = new ArrayList<>();
        for (String name : classNames) {
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (Throwable t) {
                // A class that itself fails to load in the (ai-present) test classpath is a
                // separate concern; skip so this guard reports signature offenders only.
                continue;
            }
            if (AnnotatedElementUtils.hasAnnotation(c, Controller.class)) {
                controllers.add(c);
            }
        }
        return controllers;
    }
}
