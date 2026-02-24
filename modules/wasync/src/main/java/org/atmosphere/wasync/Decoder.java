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
 * Decode a received object of type {@code U} into an object of type {@code T}
 * before dispatching it to a {@link Function}.
 *
 * @param <U> the source type (typically {@link String} or {@code byte[]})
 * @param <T> the decoded type
 */
public interface Decoder<U, T> {

    /**
     * Decode the received object.
     *
     * @param event the event that triggered the decode
     * @param s     the object to decode
     * @return the decoded object, or {@code null} to skip dispatching
     */
    T decode(Event event, U s);

    /**
     * Result of a decoding operation that can signal whether processing should continue.
     *
     * @param <T> the decoded type
     */
    record Decoded<T>(T decoded, Action action) {

        public enum Action {
            CONTINUE,
            ABORT
        }

        /**
         * A sentinel value indicating that the message should not be dispatched.
         */
        @SuppressWarnings("rawtypes")
        public static final Decoded ABORT = new Decoded<>(null, Action.ABORT);

        public static <T> Decoded<T> of(T value) {
            return new Decoded<>(value, Action.CONTINUE);
        }
    }
}
