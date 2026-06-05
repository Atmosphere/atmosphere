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
package org.atmosphere.mcp.runtime;

import org.atmosphere.mcp.registry.McpRegistry;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * The continuation for an {@code input_required} task (SEP-2663 + SEP-2322):
 * everything needed to re-run a long-running tool once the client supplies the
 * input it requested. Held <em>on</em> the {@link McpTask} so it is evicted with
 * the task — there is no separate, unbounded resumption store to leak.
 *
 * @param tool        the tool to re-invoke
 * @param arguments   the original {@code tools/call} arguments
 * @param principal   the authenticated caller, captured at create time
 * @param accumulated input responses gathered from prior rounds
 * @param inputRequests the outstanding requests surfaced to the client via {@code tasks/get}
 */
record TaskResumption(McpRegistry.ToolEntry tool, JsonNode arguments, String principal,
                      Map<String, Object> accumulated, Map<String, Object> inputRequests) {
}
