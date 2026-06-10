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

import org.atmosphere.ai.identity.OAuthOnBehalfOfCredentialStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the opt-in wiring of the OAuth on-behalf-of credential vault (P1.6). */
class AtmosphereOAuthOboAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @Test
    void offByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(OAuthOnBehalfOfCredentialStore.class));
    }

    @Test
    void registeredWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.identity.oauth-obo.enabled=true",
                        "atmosphere.ai.identity.oauth-obo.token-endpoint=https://login.example.com/oauth2/token",
                        "atmosphere.ai.identity.oauth-obo.client-id=agent-client",
                        "atmosphere.ai.identity.oauth-obo.client-secret=shh",
                        "atmosphere.ai.identity.oauth-obo.default-scope=https://api.example.com/.default")
                .run(context -> {
                    assertThat(context).hasSingleBean(OAuthOnBehalfOfCredentialStore.class);
                    assertThat(context.getBean(OAuthOnBehalfOfCredentialStore.class).name())
                            .isEqualTo("oauth-obo");
                });
    }
}
