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
package org.atmosphere.samples.springboot.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.spring.boot.AtmosphereConsoleInfoEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Dynamic backstop for the {@code NoClassDefFoundError: org/atmosphere/ai/...}
 * startup crash — the runtime twin of the starter's
 * {@code AlwaysActiveOptionalAiTypeIsolationTest}.
 *
 * <p>{@code spring-boot-durable-sessions} pulls the Atmosphere Spring Boot
 * starter but <em>not</em> {@code atmosphere-ai} (the starter declares that
 * dependency {@code <optional>true}, so it never reaches this consumer). Booting
 * the full auto-configuration on this genuinely AI-free classpath is exactly the
 * scenario that crashed when the console Tape tab first shipped: Spring reflects
 * over every always-active {@code @RestController} at registration, and
 * {@link Class#getDeclaredMethods()} force-loads each method's parameter and
 * return types. A stray {@code org.atmosphere.ai.*} type in one of those
 * signatures throws {@link NoClassDefFoundError} and the whole context refresh
 * fails here — turning a downstream user's startup crash into a build failure.
 */
@SpringBootTest
class AiFreeStartupSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithTheAlwaysActiveSurfacesOnAnAiFreeClasspath() {
        // Premise check: this guard only means something if the classpath is truly AI-free.
        // If atmosphere-ai ever creeps in transitively, the trap can no longer be reproduced
        // here, so fail loudly rather than pass a hollow smoke.
        assertThatThrownBy(() -> Class.forName("org.atmosphere.ai.tape.TapeStore"))
                .as("durable-sessions must stay AI-free for this boot smoke to guard anything")
                .isInstanceOf(ClassNotFoundException.class);

        // The context refreshed at all — so no always-active bean force-loaded an absent AI type.
        assertThat(context.getBean(AtmosphereFramework.class))
                .as("runtime came up on the AI-free classpath")
                .isNotNull();

        // The console info endpoint is the always-active @RestController that carried the
        // original tape-type regression; its presence proves the surface registered cleanly.
        assertThat(context.getBean(AtmosphereConsoleInfoEndpoint.class))
                .as("always-active console surface registered without a NoClassDefFoundError")
                .isNotNull();
    }
}
