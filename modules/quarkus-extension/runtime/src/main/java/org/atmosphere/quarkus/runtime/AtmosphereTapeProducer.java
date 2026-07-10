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

import org.atmosphere.ai.tape.InMemoryTapeStore;
import org.atmosphere.ai.tape.TapeRecorder;
import org.atmosphere.ai.tape.TapeStore;
import org.atmosphere.ai.tape.TapeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus port of the Spring Boot starters' {@code TapeInstaller} (in
 * {@code AtmosphereAiAutoConfiguration}). When {@code atmosphere-ai} is on
 * the classpath the deployment processor registers this bean. On startup,
 * when {@code quarkus.atmosphere.ai.tape.enabled=true}, it resolves a
 * {@link TapeStore} and installs a {@link TapeRecorder} via
 * {@link TapeSupport}, so every AI streaming session crossing the endpoint or
 * pipeline dispatch path is recorded as an append-only per-run step log —
 * as-produced at the session boundary, post-decorator.
 *
 * <p>The store is resolved as: a user-supplied {@link TapeStore} CDI bean,
 * else the bundled crash-durable SQLite store when the optional
 * {@code atmosphere-checkpoint} module is present, else the in-memory store
 * with a NOT-crash-durable warning (Correctness Invariant #5).</p>
 *
 * <p>{@link #onShutdown(ShutdownEvent)} uninstalls the recorder and closes a
 * store this bean created (but never a user-supplied bean) so a Quarkus
 * dev-mode live reload does not leak the previous store — symmetric to
 * {@code DisposableBean.destroy()} in the Spring configuration (Ownership,
 * Correctness Invariant #1).</p>
 */
@ApplicationScoped
public class AtmosphereTapeProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereTapeProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable, so an absent TapeStore bean does not
    // make this producer's injection unresolvable; isResolvable() == exactly one.
    @Inject
    Instance<TapeStore> storeInstance;

    // Non-null only when this bean created the store — so onShutdown() closes a
    // store we own but never a user-supplied bean (Correctness Invariant #1).
    private volatile TapeStore ownedStore;
    // Non-null only when this bean's install won the holder — a refused
    // double-install must not uninstall the other owner's recorder.
    private volatile TapeRecorder recorder;
    private volatile boolean installed;

    /**
     * Installs the session tape on application startup when
     * {@code quarkus.atmosphere.ai.tape.enabled=true}.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires the
     *              observer eagerly)
     */
    public void onStart(@Observes @Priority(130) StartupEvent event) {
        if (installed) {
            return;
        }
        var tape = config.ai().tape();
        // Tri-state mirrors durable-runs.enabled for a uniform config surface;
        // no preset implies the tape, so unset and explicit false both keep
        // the recorder out.
        if (!tape.enabled().orElse(false)) {
            return;
        }
        TapeStore store;
        boolean created;
        if (storeInstance.isResolvable()) {
            store = storeInstance.get();
            created = false;
        } else {
            store = resolveBundledStore();
            created = true;
        }
        var recorderConfig = new TapeRecorder.Config(
                tape.queueCapacity(), tape.maxTextChars(),
                tape.idleTimeout(), tape.textFlushInterval());
        var installedRecorder = TapeSupport.install(store, recorderConfig);
        if (installedRecorder.store() != store) {
            // TapeSupport refused a double-install and returned the earlier
            // recorder. This bean owns neither that recorder nor its store, so
            // onShutdown() must leave both alone; the store created for the
            // refused install would leak otherwise — close it now (Terminal
            // Path Completeness, Invariant #2).
            if (created) {
                closeQuietly(store);
            }
            return;
        }
        this.recorder = installedRecorder;
        if (created) {
            this.ownedStore = store;
        }
        installed = true;
        if (store.durable()) {
            logger.info("Session tape enabled (store={}, crash-durable, maxRuns={}, "
                    + "maxStepsPerRun={})", store.name(), store.maxRuns(), store.maxStepsPerRun());
        } else {
            logger.warn("Session tape enabled but store '{}' is in-memory — NOT crash-durable. "
                    + "Add the atmosphere-checkpoint dependency (store=sqlite) for crash survival "
                    + "(Correctness Invariant #5).", store.name());
        }
    }

    private TapeStore resolveBundledStore() {
        var tape = config.ai().tape();
        var maxRuns = tape.maxRuns();
        var maxSteps = tape.maxStepsPerRun();
        var wantsSqlite = "sqlite".equalsIgnoreCase(tape.store());
        var sqlitePresent = isClassPresent("org.atmosphere.checkpoint.SqliteTapeStore");
        if (wantsSqlite && sqlitePresent) {
            var path = tape.path().replace(
                    "${java.io.tmpdir}", System.getProperty("java.io.tmpdir"));
            try {
                return createSqliteStore(path, maxRuns, maxSteps);
            } catch (RuntimeException e) {
                logger.error("Failed to open the SQLite tape store at {} — falling back to the "
                        + "in-memory store (NOT crash-durable)", path, e);
                return new InMemoryTapeStore(maxRuns, maxSteps);
            }
        }
        if (wantsSqlite) {
            logger.warn("quarkus.atmosphere.ai.tape.store=sqlite but the atmosphere-checkpoint module "
                    + "is not on the classpath — using the in-memory store (NOT crash-durable). Add "
                    + "the atmosphere-checkpoint dependency for crash survival.");
        }
        return new InMemoryTapeStore(maxRuns, maxSteps);
    }

    /**
     * Constructs the bundled crash-durable SQLite tape store
     * ({@code org.atmosphere.checkpoint.SqliteTapeStore}) entirely through
     * reflection, so no reachable bytecode here holds a compile-time reference to
     * that optional type.
     *
     * <p>GraalVM native-image links every reachable class at build time (the global
     * {@code --link-at-build-time}) and will hard-fail on a {@code new
     * SqliteTapeStore(...)} — or any other resolved reference — whenever the
     * checkpoint module is absent (e.g. the quarkus-chat sample). A direct call into
     * a helper class does not help: GraalVM's reflection plug-in folds a constant
     * {@code getDeclaredMethod("create")} and registers that method as an analysis
     * root, which then parses its {@code new SqliteTapeStore}. The only robust
     * shape is to pass every potentially-absent class name to {@link #loadClass} /
     * {@link #initClass} as a <em>parameter</em> (not a constant) — the same
     * indirection the {@link #isClassPresent(String)} guard above relies on — and to
     * instantiate via {@code getConstructor().newInstance()} on the runtime-loaded
     * {@link Class}. Reached only after the {@code sqlitePresent} guard, so on a
     * standard JVM this is a plain reflective construction.</p>
     *
     * <p>In a native image that <em>does</em> bundle checkpoint, the application must
     * register {@code SqliteTapeStore} (constructor) and {@code org.sqlite.JDBC}
     * for reflection; absent that the call falls back to the in-memory store,
     * logged by the caller.</p>
     */
    private static TapeStore createSqliteStore(String path, int maxRuns, int maxSteps) {
        try {
            var storeClass = loadClass("org.atmosphere.checkpoint.SqliteTapeStore");
            // Quarkus' isolated runtime classloader does not ServiceLoader-discover
            // the SQLite JDBC driver; initialise it via the store's own classloader
            // (which carries the transitive sqlite-jdbc) so DriverManager finds it.
            initClass("org.sqlite.JDBC", storeClass.getClassLoader());
            var constructor = storeClass.getConstructor(java.nio.file.Path.class, int.class, int.class);
            return (TapeStore) constructor.newInstance(java.nio.file.Path.of(path), maxRuns, maxSteps);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Surface the store's own failure (e.g. unwritable path) so the
            // caller's RuntimeException handler falls back to the in-memory store.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed to open the bundled SQLite tape store", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "SqliteTapeStore unavailable despite atmosphere-checkpoint on the classpath", e);
        }
    }

    // name reaches Class.forName as a parameter (not a compile-time constant at the
    // call site) with initialize=false, so GraalVM cannot constant-fold the lookup
    // and link the loaded class at native build time — same pattern as isClassPresent.
    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, AtmosphereTapeProducer.class.getClassLoader());
    }

    // As loadClass, but runs the class initializer (here: registers the JDBC driver).
    // The name is still a parameter so the native build does not link it eagerly.
    private static void initClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class.forName(name, true, loader);
    }

    /**
     * Uninstalls the recorder on shutdown and closes a store this bean created,
     * keeping dev-mode live reload from leaking the previous store.
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires the
     *              observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        if (!installed) {
            return;
        }
        TapeSupport.uninstall(recorder);
        installed = false;
        recorder = null;
        var owned = ownedStore;
        if (owned != null) {
            closeQuietly(owned);
        }
        ownedStore = null;
    }

    private static void closeQuietly(TapeStore store) {
        try {
            store.close();
        } catch (Exception e) {
            logger.debug("Error closing the session-tape store on shutdown", e);
        }
    }

    /**
     * Accessor used by tests to confirm the recorder was installed during startup.
     *
     * @return {@code true} once {@link #onStart(StartupEvent)} has installed the recorder
     */
    public boolean installed() {
        return installed;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, AtmosphereTapeProducer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
