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
package org.atmosphere.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects optional modules on the classpath via {@link Class#forName(String)}.
 * Results are cached for the lifetime of the JVM.
 */
public final class ClasspathDetector {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    // Sentinel class names for each optional module
    private static final String A2A_CLASS = "org.atmosphere.a2a.registry.A2aRegistry";
    private static final String MCP_CLASS = "org.atmosphere.mcp.annotation.McpServer";
    private static final String AGUI_CLASS = "org.atmosphere.agui.annotation.AgUiEndpoint";
    private static final String CHANNELS_CLASS = "org.atmosphere.channels.IncomingMessage";

    private ClasspathDetector() {
    }

    /**
     * Returns {@code true} if the given class is available on the classpath.
     */
    public static boolean isPresent(String className) {
        return CACHE.computeIfAbsent(className, name -> {
            try {
                Class.forName(name, false, Thread.currentThread().getContextClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
    }

    public static boolean hasA2a() {
        return isPresent(A2A_CLASS);
    }

    public static boolean hasMcp() {
        return isPresent(MCP_CLASS);
    }

    public static boolean hasAgUi() {
        return isPresent(AGUI_CLASS);
    }

    public static boolean hasChannels() {
        return isPresent(CHANNELS_CLASS);
    }

    /**
     * Clears the detection cache. Intended for testing only.
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
