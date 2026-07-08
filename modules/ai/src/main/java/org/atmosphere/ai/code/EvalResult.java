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
package org.atmosphere.ai.code;

/**
 * Outcome of one {@code eval} call. Exactly one of {@link #value()} /
 * {@link #error()} is meaningful, indicated by {@link #ok()}. Errors are
 * returned as data (never thrown to the model) so the model can correct course,
 * mirroring the file-tool error surface.
 *
 * @param ok        whether evaluation completed without error
 * @param value     the serialized result value when {@link #ok()} is {@code true}
 * @param error     the failure message when {@link #ok()} is {@code false}
 * @param truncated whether {@link #value()} was capped at the output limit
 */
public record EvalResult(boolean ok, String value, String error, boolean truncated) {

    /** A successful result carrying the (possibly truncated) serialized value. */
    public static EvalResult ok(String value, boolean truncated) {
        return new EvalResult(true, value, null, truncated);
    }

    /** A failed result carrying a boundary-safe error message. */
    public static EvalResult error(String message) {
        return new EvalResult(false, null, message, false);
    }
}
