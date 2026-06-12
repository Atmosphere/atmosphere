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

import org.atmosphere.auth.TokenValidator;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.interceptor.AuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The transport {@link AuthInterceptor} is installed when a
 * {@link TokenValidator} bean is present — but
 * {@code atmosphere.auth.transport-enabled=false} keeps the validator (for
 * the admin write surface) without gating the WebSocket/SSE transport. The
 * guarded-email sample relies on this so its anonymous console still
 * connects while {@code /api/admin/verifier/check} stays authenticated.
 */
class AtmosphereAuthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtmosphereAuthAutoConfiguration.class))
            .withBean(AtmosphereFramework.class, () -> mock(AtmosphereFramework.class))
            .withBean(TokenValidator.class, () -> token -> new TokenValidator.Valid("operator"));

    @Test
    void interceptorIsInstalledByDefaultWhenAValidatorIsPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AuthInterceptor.class));
    }

    @Test
    void interceptorIsSuppressedWhenTransportAuthDisabled() {
        runner.withPropertyValues("atmosphere.auth.transport-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AuthInterceptor.class));
    }
}
