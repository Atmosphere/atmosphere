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
/**
 * {@link org.atmosphere.ai.extensibility.ToolExtensibilityPoint} — the
 * primitive for "how agents acquire new capabilities at runtime". Combines
 * bounded tool discovery ({@link org.atmosphere.ai.extensibility.ToolIndex}
 * + {@link org.atmosphere.ai.extensibility.DynamicToolSelector}) with
 * per-user MCP credential resolution
 * ({@link org.atmosphere.ai.extensibility.McpTrustProvider}).
 */
package org.atmosphere.ai.extensibility;
