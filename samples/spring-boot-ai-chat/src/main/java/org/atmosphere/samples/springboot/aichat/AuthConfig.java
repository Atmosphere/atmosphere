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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.auth.TokenRefresher;
import org.atmosphere.auth.TokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo authentication configuration.
 *
 * <p>Provides a simple token-based auth system for demonstration purposes.
 * In production, replace the {@link TokenValidator} with JWT validation
 * (e.g., using the atmosphere-auth-jwt module).</p>
 */
@Configuration
@RestController
public class AuthConfig {

    private static final Logger logger = LoggerFactory.getLogger(AuthConfig.class);

    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();

    @Value("${atmosphere.auth.demo-token:demo-token}")
    private String demoToken;

    @Bean
    public TokenValidator tokenValidator() {
        validTokens.add(demoToken);
        return token -> {
            if (token.startsWith("expired-")) {
                return new TokenValidator.Expired("Token expired");
            }
            if (validTokens.contains(token)) {
                return new TokenValidator.Valid("demo-user", Map.of("token", token));
            }
            return new TokenValidator.Invalid("Unknown token");
        };
    }

    @Bean
    public TokenRefresher tokenRefresher() {
        return expiredToken -> {
            var newToken = "refreshed-" + System.currentTimeMillis();
            validTokens.add(newToken);
            logger.info("Refreshed token: {} -> {}", expiredToken, newToken);
            return Optional.of(newToken);
        };
    }

    @PostMapping("/api/auth/login")
    public Map<String, String> login(@RequestBody Map<String, String> credentials) {
        var username = credentials.getOrDefault("username", "anonymous");
        logger.info("Login: {}", username);
        return Map.of("token", demoToken);
    }
}
