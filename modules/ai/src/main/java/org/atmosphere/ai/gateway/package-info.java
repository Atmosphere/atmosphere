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
 * {@link org.atmosphere.ai.gateway.AiGateway} — outbound facade for every LLM
 * call leaving Atmosphere. Consolidates per-user rate limiting, per-user
 * credential resolution, and unified tracing under one named primitive on
 * top of the existing router / budget / metrics machinery.
 */
package org.atmosphere.ai.gateway;
