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
package org.atmosphere.ai;

import org.atmosphere.cpr.AtmosphereInitializationException;

/**
 * {@link AiConfig#RUNTIME_PROPERTY} names no available {@link AgentRuntime}.
 * Thrown by {@link AgentRuntimeResolver}; as an
 * {@link AtmosphereInitializationException} it fails the framework's
 * initialization when an {@code @AiEndpoint}, {@code @Agent} or
 * {@code @Coordinator} is registered, so a plain servlet deployment does not
 * start with its AI endpoints missing, as the Spring Boot starters and the
 * Quarkus extension fail their startup.
 */
public final class AgentRuntimeSelectionException extends AtmosphereInitializationException {

    AgentRuntimeSelectionException(String message) {
        super(message);
    }
}
