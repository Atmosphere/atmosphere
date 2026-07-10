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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.tape.TapeStore;
import org.atmosphere.checkpoint.SqliteTapeStore;

import java.nio.file.Path;

/**
 * Isolated construction of the bundled {@link SqliteTapeStore}. Kept in its
 * own class so it is class-loaded only after the autoconfig has confirmed the
 * {@code atmosphere-checkpoint} module is on the classpath — referencing it
 * directly from the autoconfig would force a {@code NoClassDefFoundError} when
 * the optional dependency is absent. Package-private: an implementation detail of
 * {@code AtmosphereAiAutoConfiguration}'s session-tape wiring.
 */
final class SqliteTapeStoreFactory {

    private SqliteTapeStoreFactory() {
    }

    static TapeStore create(String path, int maxRuns, int maxStepsPerRun) {
        return new SqliteTapeStore(Path.of(path), maxRuns, maxStepsPerRun);
    }
}
