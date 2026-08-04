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
package org.atmosphere.quarkus.admin.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.undertow.deployment.ServletBuildItem;

/**
 * Quarkus deployment processor for the Atmosphere Admin extension.
 * Registers CDI beans, reflection targets, and Jandex indexing.
 */
public class AdminProcessor {

    private static final String FEATURE = "atmosphere-admin";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    IndexDependencyBuildItem indexAdminModule() {
        return new IndexDependencyBuildItem("org.atmosphere", "atmosphere-admin");
    }

    @BuildStep
    IndexDependencyBuildItem indexAdminRuntime() {
        return new IndexDependencyBuildItem("org.atmosphere",
                "atmosphere-quarkus-admin-extension");
    }

    @BuildStep
    void registerBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(
                AdditionalBeanBuildItem.unremovableOf(
                        "org.atmosphere.quarkus.admin.runtime.AdminProducer"));
        additionalBeans.produce(
                AdditionalBeanBuildItem.unremovableOf(
                        "org.atmosphere.quarkus.admin.runtime.AdminResource"));
    }

    /**
     * Serves the bundled Atmosphere chat Console at {@code /atmosphere/console/*}.
     * The mapping is more specific than the AtmosphereServlet's {@code /atmosphere/*},
     * so by Servlet-spec URL pattern resolution this servlet wins inside its
     * subtree and the SPA bundle is reachable instead of being shadowed by the
     * 404 path of {@link org.atmosphere.cpr.AtmosphereServlet}. See
     * {@code AtmosphereConsoleServlet} for the streaming details.
     */
    @BuildStep
    ServletBuildItem registerConsoleServlet() {
        return ServletBuildItem.builder("AtmosphereConsoleServlet",
                        "org.atmosphere.quarkus.admin.runtime.AtmosphereConsoleServlet")
                .addMapping("/atmosphere/console/*")
                .setLoadOnStartup(2)
                .build();
    }

    /**
     * Make the bundled Admin and Console SPA assets reachable in native image.
     * Without this they get pruned because the resources are read via
     * reflection-style classpath lookup or Quarkus static-resource lookup, not
     * annotation-driven discovery.
     *
     * <p>Only the two stable entry points are named literally. Everything under
     * {@code console/assets/} is matched by pattern instead, because Vite stamps
     * a content hash into every bundle filename and rebuilding the SPA changes
     * them all. Naming them literally is how this rotted: the two hashes
     * previously hard-coded here
     * ({@code index-D4Ey4XUD.js} / {@code index-5mdPW76Z.css}) had stopped
     * matching the packaged bundle, so in native image the console's only
     * script and stylesheet were pruned and the whole SPA served a blank page —
     * while JVM mode, which resolves assets at runtime, kept working and hid it.
     * The three lazily-imported transport chunks (a2a / agui / grpc) were never
     * registered at all. A glob cannot drift out of sync with a rebuild.</p>
     */
    @BuildStep
    void registerConsoleResources(BuildProducer<NativeImageResourceBuildItem> resources,
                                  BuildProducer<NativeImageResourcePatternsBuildItem> patterns) {
        resources.produce(new NativeImageResourceBuildItem(
                "META-INF/resources/admin/index.html",
                "META-INF/resources/atmosphere/console/index.html"));
        patterns.produce(NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("META-INF/resources/atmosphere/console/assets/*")
                .build());
    }

    @BuildStep
    void registerReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(
                                "org.atmosphere.admin.AtmosphereAdmin",
                                "org.atmosphere.admin.AdminEventHandler",
                                "org.atmosphere.admin.AdminEventProducer",
                                "org.atmosphere.admin.ControlAuditLog",
                                "org.atmosphere.admin.ControlAuditLog$AuditEntry",
                                "org.atmosphere.admin.framework.FrameworkController",
                                "org.atmosphere.admin.agent.AgentController",
                                "org.atmosphere.quarkus.admin.runtime.AdminResource",
                                "org.atmosphere.quarkus.admin.runtime.AdminProducer")
                        .constructors()
                        .methods()
                        .fields()
                        .reason("Atmosphere Admin control plane")
                        .build());
    }
}
