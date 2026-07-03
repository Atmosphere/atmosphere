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
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.atmosphere.ai.resume.DurableRunConfig;
import org.atmosphere.ai.resume.DurableRunSpine;
import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.ai.resume.EffectJournal;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Quarkus port of the Spring Boot starter's {@code DurableRunSpineInstaller}
 * (in {@code AtmosphereAiAutoConfiguration}). When {@code atmosphere-ai} is on
 * the classpath the deployment processor registers this bean. On startup, when
 * {@code quarkus.atmosphere.durable-runs.enabled=true} — or the agent-harness
 * preset implies it ({@code quarkus.atmosphere.ai.harness.enabled=true}
 * unless the operator opts out with
 * {@code quarkus.atmosphere.ai.harness.durable-runs=false}) — it resolves an
 * {@link EffectJournal} and installs the effect-journal-backed
 * {@link DurableRunSpine} via {@link DurableRunSpineHolder}, so committed LLM
 * rounds and tool calls replay deterministically after a crash.
 *
 * <p>The journal is resolved as: a user-supplied {@link EffectJournal} CDI bean,
 * else the bundled crash-durable SQLite store when the optional
 * {@code atmosphere-checkpoint} module is present, else the in-memory journal
 * with a NOT-crash-durable warning (Correctness Invariant #5).</p>
 *
 * <p>{@link #onShutdown(ShutdownEvent)} resets the holder and closes a journal
 * this bean created (but never a user-supplied bean) so a Quarkus dev-mode live
 * reload does not leak the previous journal — symmetric to
 * {@code DisposableBean.destroy()} in the Spring configuration (Ownership,
 * Correctness Invariant #1).</p>
 */
@ApplicationScoped
public class AtmosphereDurableRunsProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereDurableRunsProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable, so an absent EffectJournal bean does not
    // make this producer's injection unresolvable; isResolvable() == exactly one.
    @Inject
    Instance<EffectJournal> journalInstance;

    // Non-null only when this bean created the journal — so onShutdown() closes a
    // journal we own but never a user-supplied bean (Correctness Invariant #1).
    private volatile EffectJournal ownedJournal;
    private volatile boolean installed;

    /**
     * Installs the durable-run spine on application startup when
     * {@code quarkus.atmosphere.durable-runs.enabled=true} or the agent-harness
     * preset implies it (see the class Javadoc).
     *
     * @param event the Quarkus startup event (unused, present so Arc fires the
     *              observer eagerly)
     */
    public void onStart(@Observes @Priority(120) StartupEvent event) {
        if (installed) {
            return;
        }
        var durable = config.durableRuns();
        // An explicitly enabled harness implies durable runs: @WithDefault
        // cannot distinguish an unset durable-runs.enabled from an explicit
        // false, so the harness's opt-out is its own key
        // (ai.harness.durable-runs) and an explicit durable-runs.enabled=true
        // always wins — see AtmosphereConfig.Ai.Harness. The tri-state switch
        // implies the spine only on a literal true; unset and the false kill
        // switch both leave durable runs at their own default.
        var harness = config.ai().harness();
        var impliedByHarness = harness.enabled().orElse(false) && harness.durableRuns();
        if (!durable.enabled() && !impliedByHarness) {
            return;
        }
        if (!durable.enabled()) {
            logger.info("Durable agent runs implied by the agent-harness preset "
                    + "(opt out with quarkus.atmosphere.ai.harness.durable-runs=false)");
        }
        EffectJournal journal;
        if (journalInstance.isResolvable()) {
            journal = journalInstance.get();
        } else {
            journal = resolveBundledJournal();
            ownedJournal = journal;
        }
        var spineConfig = new DurableRunConfig(true, durable.leaseTtl(), durable.retainOnSuccess());
        var owner = "atmosphere-" + UUID.randomUUID();
        DurableRunSpineHolder.install(new DurableRunSpine(journal, spineConfig, owner));
        installed = true;
        if (journal.durable()) {
            logger.info("Durable agent runs enabled (journal={}, crash-durable, retainOnSuccess={})",
                    journal.name(), durable.retainOnSuccess());
        } else {
            logger.warn("Durable agent runs enabled but journal '{}' is in-memory — NOT crash-durable. "
                    + "Add the atmosphere-checkpoint dependency (journal=sqlite) for crash survival "
                    + "(Correctness Invariant #5).", journal.name());
        }
    }

    private EffectJournal resolveBundledJournal() {
        var durable = config.durableRuns();
        var maxRuns = durable.maxRuns();
        var maxEffects = durable.maxEffectsPerRun();
        var wantsSqlite = "sqlite".equalsIgnoreCase(durable.journal());
        var sqlitePresent = isClassPresent("org.atmosphere.checkpoint.SqliteEffectJournal");
        if (wantsSqlite && sqlitePresent) {
            var path = durable.path().replace(
                    "${java.io.tmpdir}", System.getProperty("java.io.tmpdir"));
            try {
                return createSqliteJournal(path, maxRuns, maxEffects);
            } catch (RuntimeException e) {
                logger.error("Failed to open the SQLite effect journal at {} — falling back to the "
                        + "in-memory journal (NOT crash-durable)", path, e);
                return new InMemoryEffectJournal(maxRuns, maxEffects);
            }
        }
        if (wantsSqlite) {
            logger.warn("quarkus.atmosphere.durable-runs.journal=sqlite but the atmosphere-checkpoint module "
                    + "is not on the classpath — using the in-memory journal (NOT crash-durable). Add the "
                    + "atmosphere-checkpoint dependency for crash survival.");
        }
        return new InMemoryEffectJournal(maxRuns, maxEffects);
    }

    /**
     * Constructs the bundled crash-durable SQLite effect journal
     * ({@code org.atmosphere.checkpoint.SqliteEffectJournal}) entirely through
     * reflection, so no reachable bytecode here holds a compile-time reference to
     * that optional type.
     *
     * <p>GraalVM native-image links every reachable class at build time (the global
     * {@code --link-at-build-time}) and will hard-fail on a {@code new
     * SqliteEffectJournal(...)} — or any other resolved reference — whenever the
     * checkpoint module is absent (e.g. the quarkus-chat sample). A direct call into
     * a helper class does not help: GraalVM's reflection plug-in folds a constant
     * {@code getDeclaredMethod("create")} and registers that method as an analysis
     * root, which then parses its {@code new SqliteEffectJournal}. The only robust
     * shape is to pass every potentially-absent class name to {@link #loadClass} /
     * {@link #initClass} as a <em>parameter</em> (not a constant) — the same
     * indirection the {@link #isClassPresent(String)} guard above relies on — and to
     * instantiate via {@code getConstructor().newInstance()} on the runtime-loaded
     * {@link Class}. Reached only after the {@code sqlitePresent} guard, so on a
     * standard JVM this is a plain reflective construction.</p>
     *
     * <p>In a native image that <em>does</em> bundle checkpoint, the application must
     * register {@code SqliteEffectJournal} (constructor) and {@code org.sqlite.JDBC}
     * for reflection; absent that the call falls back to the in-memory journal,
     * logged by the caller.</p>
     */
    private static EffectJournal createSqliteJournal(String path, int maxRuns, int maxEffects) {
        try {
            var journalClass = loadClass("org.atmosphere.checkpoint.SqliteEffectJournal");
            // Quarkus' isolated runtime classloader does not ServiceLoader-discover
            // the SQLite JDBC driver; initialise it via the journal's own classloader
            // (which carries the transitive sqlite-jdbc) so DriverManager finds it.
            initClass("org.sqlite.JDBC", journalClass.getClassLoader());
            var constructor = journalClass.getConstructor(java.nio.file.Path.class, int.class, int.class);
            return (EffectJournal) constructor.newInstance(java.nio.file.Path.of(path), maxRuns, maxEffects);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Surface the journal's own failure (e.g. unwritable path) so the
            // caller's RuntimeException handler falls back to the in-memory journal.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed to open the bundled SQLite effect journal", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "SqliteEffectJournal unavailable despite atmosphere-checkpoint on the classpath", e);
        }
    }

    // name reaches Class.forName as a parameter (not a compile-time constant at the
    // call site) with initialize=false, so GraalVM cannot constant-fold the lookup
    // and link the loaded class at native build time — same pattern as isClassPresent.
    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, AtmosphereDurableRunsProducer.class.getClassLoader());
    }

    // As loadClass, but runs the class initializer (here: registers the JDBC driver).
    // The name is still a parameter so the native build does not link it eagerly.
    private static void initClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class.forName(name, true, loader);
    }

    /**
     * Resets {@link DurableRunSpineHolder} on shutdown and closes a journal this
     * bean created, keeping dev-mode live reload from leaking the previous journal.
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires the
     *              observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        if (!installed) {
            return;
        }
        DurableRunSpineHolder.reset();
        installed = false;
        if (ownedJournal instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.debug("Error closing the durable-run effect journal on shutdown", e);
            }
        }
        ownedJournal = null;
    }

    /**
     * Accessor used by tests to confirm the spine was installed during startup.
     *
     * @return {@code true} once {@link #onStart(StartupEvent)} has installed the spine
     */
    public boolean installed() {
        return installed;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, AtmosphereDurableRunsProducer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
