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

import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.SqliteCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Quarkus port of the Spring Boot starter's
 * {@code AtmosphereCheckpointAutoConfiguration}. When {@code atmosphere-checkpoint}
 * is on the classpath the deployment processor registers this bean, so a
 * {@link CheckpointStore} is available out of the box — the durable-run read plane
 * ({@code AtmosphereCheckpointServlet} at {@code /api/admin/checkpoints}) and the
 * console's Checkpoints tab work on Quarkus exactly like they do on Spring Boot,
 * without the user hand-wiring persistence (Invariant #7 — cross-runtime parity).
 *
 * <p>The store is resolved as: a user-supplied {@link CheckpointStore} CDI bean
 * (in-memory, SQLite via {@code SqliteCheckpointStore}, or Postgres via
 * {@code atmosphere-checkpoint-postgres}) always wins — the {@code @Produces}
 * method is a {@link DefaultBean}, mirroring Spring's
 * {@code @ConditionalOnMissingBean}. Absent a user bean, the default is the
 * crash-durable {@link SqliteCheckpointStore} when
 * {@code quarkus.atmosphere.ai.checkpoint.store=sqlite} and the SQLite JDBC
 * driver is present, else a <em>bounded</em> {@link InMemoryCheckpointStore} with
 * an explicit startup WARN that it is NOT crash-durable and lost on restart —
 * so the development-grade default is never mistaken for a production one
 * (Runtime Truth, Correctness Invariant #5: never advertise durability that
 * isn't there).</p>
 *
 * <p>This producer owns the lifecycle only of the store it creates
 * (Ownership, Correctness Invariant #1): the {@code @Produces} method calls
 * {@code start()} on the store it built, and {@link #dispose(CheckpointStore)}
 * calls {@code stop()} on shutdown. A user-supplied {@link CheckpointStore} bean
 * is disposed by its own producer and is never started or stopped here.</p>
 */
@ApplicationScoped
public class AtmosphereCheckpointProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereCheckpointProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable, so an absent user CheckpointStore bean
    // does not make this injection unresolvable; a startup observer resolves it
    // eagerly so the store is created + started (and its WARN/INFO logs) at boot
    // rather than lazily on first inject — parity with the Spring
    // @Bean(initMethod="start") that runs at context initialization.
    @Inject
    Instance<CheckpointStore> storeInstance;

    private volatile CheckpointStore resolved;

    /**
     * Produces the default {@link CheckpointStore}, used only when the
     * application supplies none of its own ({@link DefaultBean}). Builds the
     * crash-durable SQLite store when configured and possible, else a bounded
     * in-memory store with a NOT-crash-durable WARN. The returned store is
     * already {@code start()}ed — this producer created it and therefore owns
     * its lifecycle (Invariant #1).
     *
     * @return a started {@link CheckpointStore}
     */
    @Produces
    @Singleton
    @DefaultBean
    public CheckpointStore checkpointStore() {
        var checkpoint = config.ai().checkpoint();
        var maxSnapshots = checkpoint.maxSnapshots();
        if ("sqlite".equalsIgnoreCase(checkpoint.store())) {
            var path = checkpoint.path().replace(
                    "${java.io.tmpdir}", System.getProperty("java.io.tmpdir"));
            var sqlite = tryOpenSqlite(path, maxSnapshots);
            if (sqlite != null) {
                logger.info("Atmosphere checkpoint store: SQLite at {} (crash-durable, "
                        + "maxSnapshots={})", path, maxSnapshots);
                return sqlite;
            }
            // tryOpenSqlite already logged why it fell back; drop through to in-memory.
        }
        var store = new InMemoryCheckpointStore(maxSnapshots);
        store.start();
        logger.warn("No durable CheckpointStore configured — using in-memory (bounded at {} "
                + "snapshots, lost on restart). Set quarkus.atmosphere.ai.checkpoint.store=sqlite "
                + "(needs atmosphere-checkpoint + sqlite-jdbc) or supply a CheckpointStore CDI bean "
                + "(SqliteCheckpointStore / PostgresCheckpointStore) for production "
                + "(Correctness Invariant #5).", maxSnapshots);
        return store;
    }

    /**
     * Opens and {@code start()}s the bundled crash-durable
     * {@link SqliteCheckpointStore}, or returns {@code null} (logging the reason)
     * so the caller falls back to the in-memory store — closing a half-opened
     * store if {@code start()} fails so a failed attempt leaks no JDBC connection
     * (Terminal Path Completeness, Invariant #2). {@link SqliteCheckpointStore}
     * ships in the same jar as {@link CheckpointStore}, so it is guaranteed present
     * whenever this producer runs and can be referenced directly. The SQLite JDBC
     * driver, however, is a <em>separately</em> optional dependency: it is
     * registered via the store's own classloader (Quarkus' isolated runtime
     * classloader does not ServiceLoader-discover it) through
     * {@link #initClass(String, ClassLoader)}, whose class-name parameter keeps
     * GraalVM native-image from constant-folding the lookup and hard-linking
     * {@code org.sqlite.JDBC} at build time.
     */
    private static SqliteCheckpointStore tryOpenSqlite(String path, int maxSnapshots) {
        try {
            initClass("org.sqlite.JDBC", SqliteCheckpointStore.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn("quarkus.atmosphere.ai.checkpoint.store=sqlite but the SQLite JDBC driver "
                    + "(sqlite-jdbc) is not on the classpath — using the in-memory store (NOT "
                    + "crash-durable). Add the sqlite-jdbc dependency for crash survival.");
            return null;
        }
        SqliteCheckpointStore sqlite;
        try {
            sqlite = new SqliteCheckpointStore(Path.of(path), maxSnapshots);
        } catch (RuntimeException e) {
            logger.error("Failed to open the SQLite checkpoint store at {} — falling back to the "
                    + "in-memory store (NOT crash-durable)", path, e);
            return null;
        }
        try {
            sqlite.start();
            return sqlite;
        } catch (RuntimeException e) {
            logger.error("Failed to initialize the SQLite checkpoint schema at {} — falling back "
                    + "to the in-memory store (NOT crash-durable)", path, e);
            sqlite.stop();
            return null;
        }
    }

    // The name reaches Class.forName as a parameter (not a compile-time constant
    // at the call site) with initialize=true so the driver's class initializer
    // registers it with DriverManager, while GraalVM cannot constant-fold the
    // lookup and link org.sqlite.JDBC at native build time.
    private static void initClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class.forName(name, true, loader);
    }

    /**
     * Stops a store this producer created on shutdown (Ownership, Invariant #1).
     * A CDI {@code @Disposes} disposer matches only instances produced by this
     * bean's {@code @Produces} method, so a user-supplied {@link CheckpointStore}
     * bean is never stopped here — its own producer owns its lifecycle.
     *
     * @param store the store instance this producer created
     */
    void dispose(@Disposes CheckpointStore store) {
        try {
            store.stop();
        } catch (RuntimeException e) {
            logger.debug("Error stopping the checkpoint store on shutdown", e);
        }
    }

    /**
     * Eagerly resolves the effective {@link CheckpointStore} on startup so the
     * store is created and started (and its WARN/INFO logs) at boot, and records
     * it for the test accessor.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires the
     *              observer eagerly)
     */
    public void onStart(@Observes @Priority(140) StartupEvent event) {
        resolved = storeInstance.get();
    }

    /**
     * Accessor used by tests to confirm the store resolved during startup.
     *
     * @return the resolved store, or {@code null} if startup has not run yet
     */
    public CheckpointStore resolved() {
        return resolved;
    }
}
