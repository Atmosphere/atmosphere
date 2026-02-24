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
package org.atmosphere.wasync;

/**
 * Resolves which {@link Function} should handle a received message.
 *
 * <p>The default implementation matches on the {@link Event} name or the message content.</p>
 */
public interface FunctionResolver {

    /**
     * Default resolver that matches the function name against the event name,
     * or falls back to matching on the message content.
     */
    FunctionResolver DEFAULT = (functionName, event, message) -> {
        if (functionName.equalsIgnoreCase(event.name())) {
            return true;
        }
        if (message != null && functionName.equalsIgnoreCase(message.toString())) {
            return true;
        }
        return false;
    };

    /**
     * Determine whether the given function should handle this message.
     *
     * @param functionName the registered function name
     * @param event        the event type
     * @param message      the received message (may be {@code null})
     * @return {@code true} if the function should be invoked
     */
    boolean resolve(String functionName, Event event, Object message);
}
