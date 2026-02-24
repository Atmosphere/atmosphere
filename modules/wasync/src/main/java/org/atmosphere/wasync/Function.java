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
 * A callback function invoked when an {@link Event} occurs or a message is received.
 *
 * <p>This is a {@link FunctionalInterface} and can be used with lambda expressions:</p>
 * <pre>{@code
 * socket.on(Event.MESSAGE, message -> System.out.println(message));
 * }</pre>
 *
 * @param <T> the type of the received object
 */
@FunctionalInterface
public interface Function<T> {

    /**
     * Invoked when a message of type {@code T} is received or an event occurs.
     *
     * @param t the received object
     */
    void on(T t);
}
