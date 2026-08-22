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
package org.atmosphere.quarkus.runtime;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.atmosphere.ai.voice.VoiceEndpointHandler;
import org.atmosphere.ai.voice.VoiceSessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus parity for the Spring Boot starter's voice-endpoint lifecycle (in
 * {@code AtmosphereAiAutoConfiguration}): when
 * {@code quarkus.atmosphere.ai.voice.enabled=true}, mounts
 * {@link VoiceEndpointHandler} — the production driver of the
 * speech-to-speech loop (registre#25) — on the configured path. The
 * {@code RealtimeVoiceProvider} is resolved per connect via its
 * ServiceLoader SPI.
 *
 * <p>{@link #onShutdown(ShutdownEvent)} removes the handler and closes its
 * bridges so a dev-mode live reload does not leak provider connections
 * (Ownership, Correctness Invariant #1).</p>
 */
@ApplicationScoped
public class AtmosphereVoiceProducer {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereVoiceProducer.class);

    @Inject
    AtmosphereConfig config;

    private volatile VoiceEndpointHandler handler;
    private volatile String mountedPath;

    /**
     * Mounts the voice endpoint on application startup when enabled.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer eagerly)
     */
    public void onStart(@Observes @Priority(130) StartupEvent event) {
        if (handler != null) {
            return;
        }
        var voice = config.ai().voice();
        if (!voice.enabled()) {
            return;
        }
        var framework = LazyAtmosphereConfigurator.getFramework();
        if (framework == null) {
            logger.warn("Atmosphere framework not yet available at StartupEvent — "
                    + "voice endpoint not mounted.");
            return;
        }
        var sessionConfig = new VoiceSessionConfig(voice.model().orElse(""),
                voice.voiceName().orElse(""), voice.systemPrompt().orElse(""),
                voice.inputMime().orElse(null), voice.outputMime().orElse(null));
        var voiceHandler = new VoiceEndpointHandler(sessionConfig);
        var interceptors = new java.util.LinkedList<org.atmosphere.cpr.AtmosphereInterceptor>();
        org.atmosphere.annotation.AnnotationUtil
                .defaultManagedServiceInterceptors(framework, interceptors);
        framework.addAtmosphereHandler(voice.path(), voiceHandler, interceptors);
        handler = voiceHandler;
        mountedPath = voice.path();
        logger.info("Voice endpoint mounted at {} (provider resolved per connect)", voice.path());
    }

    /**
     * Unmounts the endpoint and closes its bridges on shutdown.
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires
     *              the observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        var voiceHandler = handler;
        if (voiceHandler == null) {
            return;
        }
        handler = null;
        var framework = LazyAtmosphereConfigurator.getFramework();
        if (framework != null && mountedPath != null) {
            framework.removeAtmosphereHandler(mountedPath);
        }
        voiceHandler.destroy();
    }

    /**
     * Accessor used by tests to confirm the mount fired during startup.
     *
     * @return the mounted handler, or {@code null} when disabled or not started
     */
    public VoiceEndpointHandler mounted() {
        return handler;
    }
}
