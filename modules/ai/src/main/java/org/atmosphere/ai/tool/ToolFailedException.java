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
package org.atmosphere.ai.tool;

/**
 * Thrown by a {@link ToolExecutor} whose call failed in a way the model should
 * read verbatim: a file that does not exist, a required argument left out, a
 * store that is not bound to the session.
 *
 * <p>{@link ToolExecutionHelper} reports the call as failed (the call ends
 * with a {@code tool-error} frame, the Console's "✕ Failed") and hands the
 * model {@link #getMessage()} exactly as written, not the
 * {@code {"error":"…"}} JSON another exception becomes. The failure is
 * expected, so it is logged at {@code DEBUG} without a stack trace.</p>
 *
 * <p>Thrown from an {@code @AiTool} method, it reaches the helper wrapped in
 * the reflective call's {@link java.lang.reflect.InvocationTargetException}
 * and is unwrapped there.</p>
 */
public class ToolFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param modelText the text the model reads as the call's result, also the
     *                  error the {@code tool-error} frame reports
     */
    public ToolFailedException(String modelText) {
        super(modelText);
    }

    /**
     * @param modelText the text the model reads as the call's result, also the
     *                  error the {@code tool-error} frame reports
     * @param cause     the failure behind it
     */
    public ToolFailedException(String modelText, Throwable cause) {
        super(modelText, cause);
    }
}
