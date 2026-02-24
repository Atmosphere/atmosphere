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
 * Encode an object of type {@code U} into an object of type {@code T} before
 * sending it to the server via {@link Socket#fire(Object)}.
 *
 * @param <U> the source type
 * @param <T> the target type (typically {@link String}, {@code byte[]}, or {@link java.io.Reader})
 */
public interface Encoder<U, T> {

    /**
     * Encode the given object.
     *
     * @param s the object to encode
     * @return the encoded object
     */
    T encode(U s);
}
