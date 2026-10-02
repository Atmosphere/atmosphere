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
package org.atmosphere.integrationtests.ai.isolation;

import org.atmosphere.auth.TokenValidator;

/**
 * Authenticates the token {@code token-<name>} as {@code <name>} and rejects any
 * other, so a test can sign in as several users. Instantiated by
 * {@code AuthInterceptor} from the {@code org.atmosphere.auth.tokenValidator}
 * init parameter, as in production.
 */
public class NamedTokenValidator implements TokenValidator {

    static final String PREFIX = "token-";

    @Override
    public Result validate(String token) {
        if (token != null && token.startsWith(PREFIX) && token.length() > PREFIX.length()) {
            return new Valid(token.substring(PREFIX.length()));
        }
        return new Invalid("unknown token");
    }
}
