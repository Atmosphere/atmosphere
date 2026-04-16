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
 * {@link org.atmosphere.ai.bridge.ProtocolBridge} SPI — a named way of
 * reaching the agents running in this Atmosphere process. Every enabled
 * bridge (in-JVM, MCP, A2A, AG-UI, gRPC, plus any third-party addition)
 * makes every registered {@code @Agent} addressable via its specific
 * protocol.
 *
 * <p>Concrete implementations ship from their owning modules:</p>
 * <ul>
 *   <li>{@code InMemoryProtocolBridge} — {@code modules/coordinator}</li>
 *   <li>{@code McpProtocolBridge} — {@code modules/mcp}</li>
 *   <li>{@code A2aProtocolBridge} — {@code modules/a2a}</li>
 *   <li>{@code AgUiProtocolBridge} — {@code modules/agui}</li>
 *   <li>{@code GrpcProtocolBridge} — {@code modules/grpc}</li>
 * </ul>
 */
package org.atmosphere.ai.bridge;
