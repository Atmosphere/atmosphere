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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.auth.TokenRefresher;
import org.atmosphere.auth.TokenValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Demo authentication for the AI chat sample.
 * Activated when {@code atmosphere.auth.demo-token} is set.
 *
 * <p>Supports three token states for E2E testing:</p>
 * <ul>
 *   <li>{@code demo-token} (or login-issued token) → Valid</li>
 *   <li>{@code expired-*} prefix → Expired (triggers refresh)</li>
 *   <li>Anything else (including absent) → Invalid / rejected</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty("atmosphere.auth.demo-token")
public class AuthConfig {

    @Value("${atmosphere.auth.demo-token}")
    private String demoToken;

    @Bean
    public TokenValidator demoTokenValidator() {
        return token -> {
            if (demoToken.equals(token)) {
                return new TokenValidator.Valid("demo-user");
            }
            if (token != null && token.startsWith("expired-")) {
                return new TokenValidator.Expired("Token expired");
            }
            return new TokenValidator.Invalid("Invalid token");
        };
    }

    @Bean
    public TokenRefresher demoTokenRefresher() {
        return expiredToken -> Optional.of(demoToken);
    }

    /**
     * Login endpoint for E2E tests. Returns the demo token for any credentials.
     */
    @RestController
    static class AuthController {

        @Value("${atmosphere.auth.demo-token}")
        private String token;

        @PostMapping("/api/auth/login")
        public Map<String, String> login(@RequestBody Map<String, String> body) {
            return Map.of("token", token);
        }
    }
}
