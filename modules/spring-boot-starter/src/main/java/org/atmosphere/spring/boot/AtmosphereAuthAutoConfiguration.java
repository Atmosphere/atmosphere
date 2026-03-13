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
package org.atmosphere.spring.boot;

import org.atmosphere.auth.TokenRefresher;
import org.atmosphere.auth.TokenValidator;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.interceptor.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers an {@link AuthInterceptor} when a
 * {@link TokenValidator} bean is present in the Spring context.
 *
 * <p>To enable authentication, simply define a {@code TokenValidator} bean:</p>
 * <pre>{@code
 * @Bean
 * public TokenValidator tokenValidator() {
 *     return token -> {
 *         var claims = jwt.verify(token);
 *         return new TokenValidator.Valid(claims.getSubject());
 *     };
 * }
 * }</pre>
 *
 * <p>Optionally provide a {@link TokenRefresher} bean for server-side token refresh.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AuthInterceptor.class)
@ConditionalOnBean(TokenValidator.class)
public class AtmosphereAuthAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAuthAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AuthInterceptor authInterceptor(TokenValidator validator,
                                            @Autowired(required = false) TokenRefresher refresher,
                                            AtmosphereFramework framework) {
        var interceptor = refresher != null
                ? new AuthInterceptor(validator, refresher)
                : new AuthInterceptor(validator);
        framework.interceptor(interceptor);
        logger.info("Registered AuthInterceptor with validator={}, refresher={}",
                validator.getClass().getSimpleName(),
                refresher != null ? refresher.getClass().getSimpleName() : "none");
        return interceptor;
    }
}
