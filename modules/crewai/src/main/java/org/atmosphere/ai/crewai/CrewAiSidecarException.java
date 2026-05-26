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
package org.atmosphere.ai.crewai;

/**
 * Wraps every transport-level failure the CrewAI sidecar bridge encounters
 * so callers see one exception type rather than a mix of
 * {@link java.io.IOException}, {@link InterruptedException}, and runtime
 * parse failures. The runtime translates this into
 * {@link org.atmosphere.ai.StreamingSession#error(Throwable)} per
 * Correctness Invariant #2.
 */
public class CrewAiSidecarException extends RuntimeException {

    public CrewAiSidecarException(String message) {
        super(message);
    }

    public CrewAiSidecarException(String message, Throwable cause) {
        super(message, cause);
    }
}
