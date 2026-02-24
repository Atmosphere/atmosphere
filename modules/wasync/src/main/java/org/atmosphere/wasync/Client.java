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
 * Factory for creating {@link Socket} and {@link RequestBuilder} instances.
 *
 * <pre>{@code
 * Client client = Client.newClient();
 * Socket socket = client.create();
 * }</pre>
 */
public interface Client {

    /**
     * Create a new default client.
     *
     * @return a new client
     */
    static Client newClient() {
        return new org.atmosphere.wasync.impl.DefaultClient();
    }

    /**
     * Create a new {@link Socket} with default options.
     *
     * @return a new socket
     */
    Socket create();

    /**
     * Create a new {@link Socket} with the given options.
     *
     * @param options the options
     * @return a new socket
     */
    Socket create(Options options);

    /**
     * Create a new {@link RequestBuilder}.
     *
     * @return a new request builder
     */
    RequestBuilder<?> newRequestBuilder();

    /**
     * Create a new {@link OptionsBuilder}.
     *
     * @return a new options builder
     */
    OptionsBuilder<?, ?> newOptionsBuilder();
}
