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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AgentLifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Wave 3 sample showcase: {@link AgentLifecycleListener} that audits every
 * tool invocation by logging the tool name, sanitized argument count, and
 * a short preview of the result. Real auditing implementations would
 * forward to a metrics backend, tracing system, or security log — this
 * sample uses SLF4J for clarity.
 *
 * <p>Attach programmatically via {@code context.withListeners(List.of(new ToolAuditListener()))}
 * or through a {@link org.atmosphere.ai.AiInterceptor} that mutates the
 * request before dispatch.</p>
 */
public class ToolAuditListener implements AgentLifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(ToolAuditListener.class);

    @Override
    public void onToolCall(String toolName, Map<String, Object> arguments) {
        logger.info("[tool-audit] call tool={} args={}",
                toolName, arguments != null ? arguments.size() : 0);
    }

    @Override
    public void onToolResult(String toolName, String resultPreview) {
        var preview = resultPreview == null ? "<null>"
                : (resultPreview.length() > 80 ? resultPreview.substring(0, 80) + "..." : resultPreview);
        logger.info("[tool-audit] result tool={} preview='{}'", toolName, preview);
    }
}
