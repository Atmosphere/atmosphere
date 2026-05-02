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
package org.atmosphere.ai.quarkus.langchain4j.deployment;

import io.quarkiverse.langchain4j.deployment.RequestChatModelBeanBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import org.atmosphere.ai.quarkus.langchain4j.AtmosphereQuarkusLangChain4jBridge;

/**
 * Build-time wiring for the Atmosphere Quarkus + LangChain4j bridge:
 *
 * <ul>
 *   <li>Registers {@link AtmosphereQuarkusLangChain4jBridge} as an
 *       additional CDI bean so its {@code @Observes StartupEvent} hook
 *       fires without the consumer having to declare it.</li>
 *   <li>Adds the {@code org.atmosphere.ai.AgentRuntime} ServiceLoader
 *       descriptor and the {@code LangChain4jAgentRuntime} class to the
 *       native image so SPI lookup works on GraalVM native binaries.</li>
 * </ul>
 *
 * <p>Quarkus LangChain4j produces the {@code StreamingChatModel} synthetic
 * bean during its own deployment processor; ours runs against the resulting
 * Arc bean graph at app startup, so we deliberately do not declare a hard
 * deployment-side dependency on {@code quarkus-langchain4j-core-deployment}
 * — the {@code Instance<StreamingChatModel>} lookup is late enough that
 * extension ordering is irrelevant.</p>
 */
public class AtmosphereQuarkusLangChain4jProcessor {

    private static final String FEATURE = "atmosphere-quarkus-langchain4j";
    private static final String AGENT_RUNTIME_SPI = "META-INF/services/org.atmosphere.ai.AgentRuntime";
    private static final String LANGCHAIN4J_RUNTIME_CLASS =
            "org.atmosphere.ai.langchain4j.LangChain4jAgentRuntime";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Index {@code atmosphere-ai} so Jandex sees {@code AiEndpointProcessor},
     * which is annotated with {@code @AtmosphereAnnotation(AiEndpoint.class)}.
     * Without this, the core Quarkus extension's scan for the meta-annotation
     * misses it and {@code AnnotationHandler} cannot dispatch {@code @AiEndpoint}
     * classes to their processor — they're scanned but never registered.
     */
    @BuildStep
    IndexDependencyBuildItem indexAtmosphereAi() {
        return new IndexDependencyBuildItem("org.atmosphere", "atmosphere-ai");
    }

    @BuildStep
    AdditionalBeanBuildItem registerBridgeBean() {
        return AdditionalBeanBuildItem.unremovableOf(AtmosphereQuarkusLangChain4jBridge.class);
    }

    /**
     * Ask Quarkus LangChain4j's {@code BeansProcessor} to materialize the
     * default-name {@code ChatModel} / {@code StreamingChatModel} synthetic
     * beans. Without this no provider extension (openai, ollama, …) is ever
     * selected because Quarkus L4j only generates beans for explicitly
     * requested model names — typically driven by {@code @RegisterAiService}
     * or programmatic injection points {@code BeanDiscoveryFinishedBuildItem}
     * can see. Atmosphere's bridge uses {@link jakarta.enterprise.inject.Instance}
     * lookup, which Bean Discovery does not record as a request, so we
     * declare the request explicitly here.
     */
    @BuildStep
    RequestChatModelBeanBuildItem requestDefaultChatModelBean() {
        return new RequestChatModelBeanBuildItem("<default>");
    }

    /**
     * Native image: the SPI service file ships in atmosphere-langchain4j's
     * resources. Quarkus does not auto-include arbitrary {@code META-INF/services}
     * entries — only ones referenced by {@link java.util.ServiceLoader} calls
     * the analyzer can statically detect, which is hit-or-miss across runtimes.
     * Declaring it explicitly keeps the SPI working under {@code mvn package -Pnative}.
     */
    @BuildStep
    NativeImageResourceBuildItem registerAgentRuntimeSpiResource() {
        return new NativeImageResourceBuildItem(AGENT_RUNTIME_SPI);
    }

    @BuildStep
    ServiceProviderBuildItem registerAgentRuntimeServiceProvider() {
        return new ServiceProviderBuildItem(
                "org.atmosphere.ai.AgentRuntime",
                LANGCHAIN4J_RUNTIME_CLASS);
    }

    /**
     * The runtime class is instantiated reflectively by ServiceLoader; native
     * image needs it on the reflective registry. Methods are needed because
     * {@code AbstractAgentRuntime} dispatches via virtual calls discovered at
     * runtime.
     */
    @BuildStep
    ReflectiveClassBuildItem registerAgentRuntimeReflective() {
        return ReflectiveClassBuildItem.builder(LANGCHAIN4J_RUNTIME_CLASS)
                .methods()
                .build();
    }
}
