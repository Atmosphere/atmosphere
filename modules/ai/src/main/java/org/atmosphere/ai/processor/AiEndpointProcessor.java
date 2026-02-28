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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.DefaultAiSupportResolver;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Annotation processor for {@link AiEndpoint}. Discovered by Atmosphere's annotation
 * scanning infrastructure via {@link AtmosphereAnnotation}. Scans the annotated class
 * for a {@link Prompt} method, validates the signature, and registers an
 * {@link AiEndpointHandler} at the configured path.
 */
@AtmosphereAnnotation(AiEndpoint.class)
public class AiEndpointProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(AiEndpointProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(AiEndpoint.class);
            if (annotation == null) {
                return;
            }

            var promptMethod = findPromptMethod(annotatedClass);
            if (promptMethod == null) {
                logger.error("@AiEndpoint class {} has no @Prompt method", annotatedClass.getName());
                return;
            }

            validatePromptSignature(promptMethod);

            var instance = framework.newClassInstance(Object.class, annotatedClass);
            var systemPrompt = resolveSystemPrompt(annotation);
            var aiSupport = resolveAiSupport();
            var interceptors = instantiateInterceptors(annotation.interceptors());
            var handler = new AiEndpointHandler(instance, promptMethod,
                    annotation.timeout(), systemPrompt, aiSupport, interceptors);

            framework.addAtmosphereHandler(annotation.path(), handler, new ArrayList<>());

            logger.info("AI endpoint registered at {} (class: {}, aiSupport: {}, interceptors: {}, timeout: {}ms)",
                    annotation.path(), annotatedClass.getSimpleName(),
                    aiSupport.name(), interceptors.size(), annotation.timeout());

        } catch (Exception e) {
            logger.error("Failed to register AI endpoint from {}", annotatedClass.getName(), e);
        }
    }

    private Method findPromptMethod(Class<?> clazz) {
        Method found = null;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "@AiEndpoint class " + clazz.getName()
                                    + " has multiple @Prompt methods: "
                                    + found.getName() + " and " + method.getName()
                                    + ". Exactly one @Prompt method is required.");
                }
                found = method;
            }
        }
        return found;
    }

    /**
     * Resolves the system prompt: resource file takes precedence over inline string.
     */
    private String resolveSystemPrompt(AiEndpoint annotation) {
        var resource = annotation.systemPromptResource();
        if (resource != null && !resource.isEmpty()) {
            return PromptLoader.load(resource);
        }
        return annotation.systemPrompt();
    }

    private void validatePromptSignature(Method method) {
        var params = method.getParameterTypes();
        if (params.length < 2 || params.length > 3) {
            throw new IllegalArgumentException(
                    "@Prompt method must have 2 or 3 parameters: (String, StreamingSession[, AtmosphereResource]). Found " + params.length);
        }
        if (params[0] != String.class) {
            throw new IllegalArgumentException(
                    "@Prompt method first parameter must be String. Found " + params[0].getName());
        }
        if (!org.atmosphere.ai.StreamingSession.class.isAssignableFrom(params[1])) {
            throw new IllegalArgumentException(
                    "@Prompt method second parameter must be StreamingSession. Found " + params[1].getName());
        }
        if (params.length == 3 && !org.atmosphere.cpr.AtmosphereResource.class.isAssignableFrom(params[2])) {
            throw new IllegalArgumentException(
                    "@Prompt method third parameter must be AtmosphereResource. Found " + params[2].getName());
        }
    }

    private AiSupport resolveAiSupport() {
        var support = DefaultAiSupportResolver.resolve();
        var settings = AiConfig.get();
        if (settings != null) {
            support.configure(settings);
        }
        return support;
    }

    private List<AiInterceptor> instantiateInterceptors(Class<? extends AiInterceptor>[] classes) {
        var interceptors = new ArrayList<AiInterceptor>();
        for (var clazz : classes) {
            try {
                interceptors.add(clazz.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                logger.error("Failed to instantiate AiInterceptor: {}", clazz.getName(), e);
            }
        }
        return List.copyOf(interceptors);
    }
}
