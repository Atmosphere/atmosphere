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
package org.atmosphere.cpr;

/**
 * Fails {@link AtmosphereFramework#init()} instead of being logged and skipped.
 *
 * <p>The framework tolerates a failing annotation {@link org.atmosphere.annotation.Processor}:
 * it logs the error and starts without that class. A processor that finds the
 * application's configuration unusable (a setting naming something that does
 * not exist, say) throws this exception instead, so the framework's
 * initialization fails with it as the cause and the deployment does not start
 * half-configured.</p>
 *
 * <p>It is an {@link IllegalStateException}, so code catching that type keeps
 * catching it.</p>
 */
public class AtmosphereInitializationException extends IllegalStateException {

    public AtmosphereInitializationException(String message) {
        super(message);
    }

    public AtmosphereInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
