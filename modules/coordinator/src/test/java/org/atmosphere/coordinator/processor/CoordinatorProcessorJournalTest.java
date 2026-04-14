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
package org.atmosphere.coordinator.processor;

import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationJournalInspector;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link CoordinatorProcessor#resolveJournal} — the
 * post-release Bug 4 fix that closes the
 * Spring-bean-to-CoordinatorProcessor bridge so a Spring-wired
 * {@code CoordinationJournal} bean is actually consulted instead of silently
 * falling through to {@link CoordinationJournal#NOOP}.
 *
 * <p>Three resolution paths must all work in the same {@link CoordinatorProcessor}
 * instance, regardless of deployment style (Spring Boot / plain servlet /
 * Quarkus):</p>
 *
 * <ol>
 *   <li><strong>Default</strong>: no provider anywhere — resolve to
 *       {@link CoordinationJournal#NOOP} (processor-owned, but a no-op
 *       so {@code start()}/{@code stop()} are harmless).</li>
 *   <li><strong>Externally bridged</strong>: a journal stashed on
 *       {@code framework.getAtmosphereConfig().properties()} under
 *       {@link CoordinatorProcessor#COORDINATION_JOURNAL_PROPERTY} — this is
 *       the Spring Boot bridge path. The processor must use that journal
 *       <em>and</em> mark it as externally managed, so the {@code handle()}
 *       path skips {@code start()} and the underlying {@code CheckpointStore}
 *       is not initialized twice.</li>
 *   <li><strong>ServiceLoader</strong>: when nothing is bridged but a
 *       {@code META-INF/services/} provider exists, the processor must pick
 *       it up and own its lifecycle. (We exercise the ServiceLoader code path
 *       indirectly by leaving the bridge unset on a fresh framework — the
 *       coordinator module itself ships no provider, so the result is
 *       {@link CoordinationJournal#NOOP NOOP}; the production fallback path
 *       is the same one that worked in 4.0.37.)</li>
 * </ol>
 */
public class CoordinatorProcessorJournalTest {

    @Test
    public void resolvesNoopByDefault() {
        var framework = new AtmosphereFramework();
        var processor = new CoordinatorProcessor();

        var resolved = processor.resolveJournal(framework);

        assertNotNull(resolved, "resolveJournal must always return a result");
        assertSame(CoordinationJournal.NOOP, resolved.journal(),
                "with no bridge and no ServiceLoader provider, the journal must "
                        + "fall through to CoordinationJournal.NOOP");
        assertFalse(resolved.externallyManaged(),
                "the NOOP fallback is not externally managed — its lifecycle "
                        + "is owned by the processor (start/stop are no-ops)");
    }

    @Test
    public void resolvesBridgedJournalAsExternallyManaged() {
        var framework = new AtmosphereFramework();
        var processor = new CoordinatorProcessor();
        var bridged = new RecordingJournal();
        framework.getAtmosphereConfig().properties()
                .put(CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY, bridged);

        var resolved = processor.resolveJournal(framework);

        assertSame(bridged, resolved.journal(),
                "the journal stashed on framework properties must be returned "
                        + "verbatim — this is the Spring-bean bridge");
        assertTrue(resolved.externallyManaged(),
                "a bridged journal must be flagged externally-managed so the "
                        + "processor's handle() path skips start()/stop() and "
                        + "Spring (or whoever wired the bean) keeps full "
                        + "ownership of its lifecycle");
        assertEquals(0, bridged.startCount.get(),
                "resolveJournal must NEVER call start() on a bridged journal");
        assertEquals(0, bridged.stopCount.get(),
                "resolveJournal must NEVER call stop() on a bridged journal");
    }

    @Test
    public void ignoresBridgedNoopAndFallsThroughToServiceLoader() {
        // A NOOP value pinned on the property must not short-circuit the
        // ServiceLoader branch — otherwise a misconfigured bridge would mask
        // a legitimate META-INF/services/ provider. The contract: only a
        // non-NOOP bridged value wins.
        var framework = new AtmosphereFramework();
        var processor = new CoordinatorProcessor();
        framework.getAtmosphereConfig().properties()
                .put(CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY,
                        CoordinationJournal.NOOP);

        var resolved = processor.resolveJournal(framework);

        assertSame(CoordinationJournal.NOOP, resolved.journal(),
                "NOOP bridge value falls through to ServiceLoader; with no "
                        + "provider on the test classpath the result is NOOP");
        assertFalse(resolved.externallyManaged(),
                "fall-through path is processor-owned — same as the original "
                        + "ServiceLoader-only behavior preserved for plain-servlet "
                        + "and Quarkus deployments");
    }

    @Test
    public void bridgePropertyKeyIsStableForExternalConsumers() {
        // The constant is a public API contract that the spring-boot-starter
        // auto-configuration writes to. Pin its exact value so accidental
        // renames break this test instead of silently breaking every Spring
        // Boot consumer downstream.
        assertEquals("org.atmosphere.coordinator.journal",
                CoordinatorProcessor.COORDINATION_JOURNAL_PROPERTY,
                "COORDINATION_JOURNAL_PROPERTY is an inter-module bridge key — "
                        + "AtmosphereCoordinatorAutoConfiguration in the "
                        + "spring-boot-starter writes to this exact string. "
                        + "Do not rename without updating both sides.");
    }

    /**
     * In-memory journal that counts {@code start()}/{@code stop()} invocations
     * so the test can prove the processor never touches lifecycle methods on a
     * bridged (Spring-managed) journal.
     */
    private static final class RecordingJournal implements CoordinationJournal {

        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public void start() {
            startCount.incrementAndGet();
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
        }

        @Override
        public void record(CoordinationEvent event) {
        }

        @Override
        public List<CoordinationEvent> retrieve(String coordinationId) {
            return List.of();
        }

        @Override
        public List<CoordinationEvent> query(CoordinationQuery query) {
            return List.of();
        }

        @Override
        public CoordinationJournal inspector(CoordinationJournalInspector inspector) {
            return this;
        }
    }
}
