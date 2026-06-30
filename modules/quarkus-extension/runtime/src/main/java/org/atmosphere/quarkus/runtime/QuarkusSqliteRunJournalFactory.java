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

import org.atmosphere.ai.resume.EffectJournal;
import org.atmosphere.checkpoint.SqliteEffectJournal;

import java.nio.file.Path;

/**
 * Isolated construction of the bundled {@link SqliteEffectJournal}. Kept in its
 * own class so it is class-loaded only after {@link AtmosphereDurableRunsProducer}
 * has confirmed the {@code atmosphere-checkpoint} module is on the classpath —
 * referencing it directly from the producer would force a
 * {@code NoClassDefFoundError} when the optional dependency is absent. Mirrors the
 * Spring Boot starter's {@code SqliteRunJournalFactory}.
 */
final class QuarkusSqliteRunJournalFactory {

    private QuarkusSqliteRunJournalFactory() {
    }

    static EffectJournal create(String path, int maxRuns, int maxEffectsPerRun) {
        ensureDriverRegistered();
        return new SqliteEffectJournal(Path.of(path), maxRuns, maxEffectsPerRun);
    }

    /**
     * Quarkus' isolated runtime classloader does not run {@link java.sql.DriverManager}'s
     * ServiceLoader-based auto-discovery for the SQLite JDBC driver (a plain-JVM
     * classpath does, which is why the Spring path needs no equivalent). Loading
     * {@code org.sqlite.JDBC} runs its static initializer, which calls
     * {@code DriverManager.registerDriver(...)} on the JVM-global registry.
     *
     * <p>The driver is loaded with {@link SqliteEffectJournal}'s own classloader —
     * the one that also carries the transitive {@code sqlite-jdbc} — because
     * {@code DriverManager.getConnection} (called from {@code SqliteEffectJournal})
     * only accepts a driver visible to the caller's classloader. Registering it via
     * any other classloader would still leave {@code getConnection} with "no
     * suitable driver".
     */
    private static void ensureDriverRegistered() {
        try {
            Class.forName("org.sqlite.JDBC", true, SqliteEffectJournal.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "SQLite JDBC driver (org.sqlite.JDBC) not found on the classpath", e);
        }
    }
}
