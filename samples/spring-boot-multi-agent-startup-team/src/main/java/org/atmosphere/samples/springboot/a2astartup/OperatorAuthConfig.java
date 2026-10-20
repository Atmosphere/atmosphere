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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.auth.TokenValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authentication for the admin write surface the README's operational controls
 * use (cancel an in-flight session, disconnect a client, arm the kill switch).
 * Every admin write requires an authenticated principal (Correctness Invariant
 * #6), so {@code application.yml} enables the admin writes for this demo and this
 * validator resolves the single {@link #DEMO_OPERATOR_TOKEN} to the
 * {@code operator} principal; everything else is rejected, and an anonymous
 * caller is refused with 401.
 *
 * <p>Open {@code http://localhost:8080/?token=demo-operator}: the root redirect
 * keeps the query, the Console persists the token and replays it as
 * {@code X-Atmosphere-Auth} on admin writes. A deliberately trivial stand-in for
 * real operator auth (OIDC, JWT, mTLS).</p>
 */
@Configuration
public class OperatorAuthConfig {

    /** Demo operator token: the Console and curl present it on admin writes. */
    public static final String DEMO_OPERATOR_TOKEN = "demo-operator";

    @Bean
    public TokenValidator operatorTokenValidator() {
        return token -> DEMO_OPERATOR_TOKEN.equals(token)
                ? new TokenValidator.Valid("operator")
                : new TokenValidator.Invalid("unrecognized operator token");
    }
}
