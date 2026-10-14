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
package org.atmosphere.verifier.spi;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Lazy holder for the checker {@link SmtChecker#resolve()} returns. The
 * ServiceLoader scan and each provider's availability probe (a native solver
 * load for Z3) run once, on first use, instead of on every call.
 */
final class ResolvedSmtChecker {

    static final SmtChecker INSTANCE = ServiceLoader.load(SmtChecker.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(SmtChecker::isAvailable)
            .max(Comparator.comparingInt(SmtChecker::priority))
            .orElseGet(SmtChecker.NoOpSmtChecker::new);

    private ResolvedSmtChecker() {
    }
}
