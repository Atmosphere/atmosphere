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
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

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
