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
package org.atmosphere.ai;

/**
 * A turn refused by a governance policy or a guardrail: the expected outcome of a
 * deny or block decision, not a failure of the framework or of the application.
 *
 * <p>Pass it to {@link StreamingSession#error(Throwable)} to end a turn that a policy
 * denied. The client receives the same {@code error} frame, carrying the exception's
 * message, that any other {@link SecurityException} would produce; the session only
 * logs it on one line instead of as an error with a stack trace, since the code that
 * denied the turn has already recorded why. A policy or guardrail that <em>failed</em>
 * (threw while evaluating) is a real error and is reported with a plain
 * {@link SecurityException}, stack trace included.</p>
 */
public class RequestDeniedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message the reason shown to the client, e.g. which policy denied the turn
     */
    public RequestDeniedException(String message) {
        super(message);
    }
}
