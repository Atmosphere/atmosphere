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

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Config-driven {@link ToolPermissionPolicy} that resolves per-tool decisions
 * from properties of the form:
 *
 * <pre>
 * atmosphere.tools.permissions.default   = allow | deny | confirm
 * atmosphere.tools.permissions.&lt;tool&gt;  = allow | deny | confirm
 * </pre>
 *
 * <p>Per-tool keys override the default. Values are case-insensitive; an
 * unrecognized value falls back to the configured default (which itself
 * defaults to {@link ToolPermission#ALLOW} when the {@code default} key is
 * missing or unparseable).</p>
 *
 * <p>Two factories cover the common wiring:</p>
 * <ul>
 *   <li>{@link #fromSystemProperties()} — reads {@link System#getProperties()}
 *       live so {@code -Datmosphere.tools.permissions.delete_account=deny}
 *       on the JVM command line works without re-instantiating the policy.</li>
 *   <li>{@link #from(Properties)} — reads a caller-supplied snapshot
 *       (deterministic for tests and embedded scenarios).</li>
 * </ul>
 */
public final class PropertiesToolPermissionPolicy implements ToolPermissionPolicy {

    public static final String KEY_PREFIX = "atmosphere.tools.permissions.";
    public static final String DEFAULT_KEY = KEY_PREFIX + "default";

    private final Function<String, String> lookup;

    private PropertiesToolPermissionPolicy(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    /** Reads {@link System#getProperties()} on every lookup. */
    public static PropertiesToolPermissionPolicy fromSystemProperties() {
        return new PropertiesToolPermissionPolicy(System::getProperty);
    }

    /** Reads a frozen {@link Properties} snapshot supplied by the caller. */
    public static PropertiesToolPermissionPolicy from(Properties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        return new PropertiesToolPermissionPolicy(properties::getProperty);
    }

    @Override
    public ToolPermission decide(String toolName, Map<String, Object> args) {
        var defaultPermission = parse(lookup.apply(DEFAULT_KEY), ToolPermission.ALLOW);
        if (toolName == null || toolName.isBlank()) {
            return defaultPermission;
        }
        var raw = lookup.apply(KEY_PREFIX + toolName);
        return parse(raw, defaultPermission);
    }

    private static ToolPermission parse(String raw, ToolPermission fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "allow" -> ToolPermission.ALLOW;
            case "deny" -> ToolPermission.DENY;
            case "confirm" -> ToolPermission.CONFIRM;
            default -> fallback;
        };
    }
}
