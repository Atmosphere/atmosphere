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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.mcp.client.McpToolSource;

/**
 * Static handoff between the Spring-managed {@link RemoteToolsConfig} bean
 * and the reflectively-instantiated {@link McpToolsInterceptor}. {@code @AiEndpoint}
 * registers interceptors by class — they're created via no-arg constructor —
 * so this holder is the established way (matching {@code AiConfig} and other
 * Atmosphere SPI hooks) to bridge framework-instantiated objects with
 * Spring-managed dependencies.
 */
final class McpToolSourceHolder {

    private static volatile McpToolSource source;

    private McpToolSourceHolder() {
    }

    static void set(McpToolSource source) {
        McpToolSourceHolder.source = source;
    }

    static McpToolSource get() {
        return source;
    }

    static void clear() {
        source = null;
    }
}
