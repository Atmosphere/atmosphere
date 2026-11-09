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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus port of the Spring Boot starter's {@code LineageRecorderInstaller}
 * (in {@code AtmosphereAiAutoConfiguration}), with the same semantics (Mode
 * Parity, Correctness Invariant #7): unless
 * {@code quarkus.atmosphere.ai.lineage.enabled=false}, installs a user
 * {@link LineageRecorder} CDI bean, or else a bounded
 * {@link InMemoryLineageRecorder} of {@code capacity} entries that blanks
 * prompt and tool-result text unless {@code capture-content=true}, into the
 * process-wide {@link LineageRecorderHolder}. It installs only when no
 * recorder is installed yet, and {@link #onShutdown} takes it back out only
 * when it is still the installed one (Ownership, Correctness Invariant #1).
 */
@ApplicationScoped
public class AtmosphereLineageRecorderProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereLineageRecorderProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable; isResolvable() == exactly one bean.
    @Inject
    Instance<LineageRecorder> userRecorder;

    // Non-null only while this bean's recorder is the installed one.
    private volatile LineageRecorder installedRecorder;

    /**
     * Installs the lineage recorder at startup.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer)
     */
    public void onStartup(@Observes StartupEvent event) {
        var lineage = config.ai().lineage();
        if (!lineage.enabled()) {
            logger.debug("Lineage off (quarkus.atmosphere.ai.lineage.enabled=false)");
            return;
        }
        var recorder = userRecorder.isResolvable()
                ? userRecorder.get()
                : new InMemoryLineageRecorder(lineage.capacity(), lineage.captureContent());
        if (!LineageRecorderHolder.installIfAbsent(recorder)) {
            logger.info("Lineage: a recorder is already installed ({}); keeping it",
                    LineageRecorderHolder.get().getClass().getName());
            return;
        }
        installedRecorder = recorder;
        if (recorder instanceof InMemoryLineageRecorder buffer) {
            if (buffer.captureContent()) {
                logger.warn("Lineage records prompt and tool-result text "
                        + "(quarkus.atmosphere.ai.lineage.capture-content=true, capacity={})",
                        buffer.capacity());
            } else {
                logger.info("Lineage on: in-memory, capacity={}, prompt and tool-result "
                        + "text not kept", buffer.capacity());
            }
        } else {
            logger.info("Lineage on (user bean): {}", recorder.getClass().getName());
        }
    }

    /**
     * Takes the recorder this bean installed back out, if it is still the
     * installed one, so a dev-mode live reload does not keep a stale one.
     *
     * @param event the Quarkus shutdown event (unused)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        var recorder = installedRecorder;
        if (recorder != null) {
            LineageRecorderHolder.resetIf(recorder);
            installedRecorder = null;
        }
    }

    /**
     * The recorder this bean installed, or {@code null} when it installed
     * none (lineage off, or a recorder was already installed).
     *
     * @return the installed recorder, or {@code null}
     */
    public LineageRecorder installedRecorder() {
        return installedRecorder;
    }
}
