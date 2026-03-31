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
 * Base exception for all AI-related errors in the Atmosphere framework.
 *
 * <p>Provides a single catch target for callers that want to handle any AI
 * subsystem failure uniformly — LLM API errors, budget exhaustion, approval
 * timeouts, structured output parsing failures, etc.</p>
 *
 * <pre>{@code
 * try {
 *     session.prompt("Summarize this document");
 * } catch (AiException e) {
 *     logger.error("AI operation failed: {}", e.getMessage(), e);
 * }
 * }</pre>
 */
public class AiException extends RuntimeException {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
