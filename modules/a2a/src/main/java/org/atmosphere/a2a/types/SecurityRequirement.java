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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Maps each named security scheme (declared in
 * {@link AgentCard#securitySchemes()}) to the OAuth scopes the caller must
 * present. An empty scope list means the scheme is required but no scopes
 * are needed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityRequirement(Map<String, List<String>> schemes) {
    public SecurityRequirement {
        schemes = schemes != null ? Map.copyOf(schemes) : Map.of();
    }
}
