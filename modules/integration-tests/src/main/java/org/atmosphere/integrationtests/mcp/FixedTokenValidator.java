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
package org.atmosphere.integrationtests.mcp;

import java.util.Map;
import org.atmosphere.auth.TokenValidator;

/**
 * A trivial {@link TokenValidator} for the MCP authorization E2E: the single
 * bearer token {@code "good-token"} authenticates as {@code alice}; everything
 * else is rejected. Has a public no-arg constructor so {@code McpAuthorization}
 * can instantiate it from the {@code org.atmosphere.auth.tokenValidator}
 * init-parameter (the same loading path used in production).
 */
public class FixedTokenValidator implements TokenValidator {

    /** The one token this validator accepts. */
    public static final String GOOD_TOKEN = "good-token";

    @Override
    public Result validate(String token) {
        if (GOOD_TOKEN.equals(token)) {
            return new Valid("alice", Map.of("scope", "mcp:tools"));
        }
        return new Invalid("unknown token");
    }
}
