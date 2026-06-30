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
package org.atmosphere.checkpoint.workflow;

import org.atmosphere.ai.resume.EffectJournal;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI for executing a {@link Workflow} on a durable-execution backend. The
 * in-tree {@link InMemoryDurableExecutionProvider} runs the workflow on
 * Atmosphere's own step engine (checkpointing each step through the
 * {@link org.atmosphere.checkpoint.CheckpointStore}); an enterprise already
 * operating Temporal, DBOS, or Restate provides an adapter implementing this
 * same interface so {@code Workflow<S>} steps run on that engine instead —
 * extending the "swap one Maven dep" promise down to the durability substrate.
 *
 * <p>The heavy external SDKs are intentionally <strong>not</strong> pulled into
 * the build: this is the seam, with a dependency-free reference adapter. A
 * Temporal/DBOS adapter ships as a separate optional module, the same way remote
 * sandbox backends and provider-specific voice backends plug into their SPIs.</p>
 */
public interface DurableExecutionProvider {

    /** Backend name (e.g. {@code "in-memory"}, {@code "temporal"}). */
    String name();

    /** Whether this backend can currently execute workflows (Runtime Truth, #5). */
    boolean isAvailable();

    /**
     * Execute {@code workflow} to a terminal {@link WorkflowResult} (Completed /
     * Hibernated / Failed) on this backend, starting from {@code initialState}.
     */
    <S> WorkflowResult<S> run(Workflow<S> workflow, S initialState);

    /**
     * Whether this backend can deterministically replay an agent run from a
     * recorded effect history (the {@link EffectJournal} contract), as opposed to
     * only checkpointing {@code Workflow<S>} steps. Default {@code false}: a
     * provider opts in by also returning a journal from {@link #effectJournal()}.
     * Capability advertisement gates on this AND the resolved journal's
     * {@link EffectJournal#durable()} (Runtime Truth, Correctness Invariant #5).
     */
    default boolean supportsDeterministicReplay() {
        return false;
    }

    /**
     * The effect-history journal this backend supplies for deterministic replay,
     * or empty when it offers none. An external Temporal/DBOS adapter returns an
     * {@link EffectJournal} backed by its own event history; the in-tree path
     * binds the bundled SQLite journal via autoconfiguration. Empty by default so
     * every existing adapter stays source-compatible.
     */
    default Optional<EffectJournal> effectJournal() {
        return Optional.empty();
    }

    /**
     * Resolve the highest-priority available external provider via
     * {@link ServiceLoader}, falling back to the in-tree
     * {@link InMemoryDurableExecutionProvider} when none is registered — so a
     * caller always gets a working backend and the fallback is explicit.
     */
    static DurableExecutionProvider resolve() {
        for (var provider : ServiceLoader.load(DurableExecutionProvider.class)) {
            if (provider.isAvailable() && !(provider instanceof InMemoryDurableExecutionProvider)) {
                return provider;
            }
        }
        return new InMemoryDurableExecutionProvider();
    }
}
