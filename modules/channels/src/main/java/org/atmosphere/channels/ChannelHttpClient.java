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
package org.atmosphere.channels;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HTTP client for all channel adapters with connection pooling and timeouts.
 */
public final class ChannelHttpClient {

    private static final HttpClient INSTANCE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ChannelHttpClient() {
    }

    public static HttpClient get() {
        return INSTANCE;
    }

    /**
     * Default request timeout for platform API calls.
     */
    public static Duration requestTimeout() {
        return Duration.ofSeconds(30);
    }
}
