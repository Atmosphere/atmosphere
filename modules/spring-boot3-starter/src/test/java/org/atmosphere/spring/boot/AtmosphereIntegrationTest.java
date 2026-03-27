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

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AtmosphereIntegrationTest {

    @Autowired
    private AtmosphereFramework framework;

    @Test
    void frameworkIsInitialized() {
        assertThat(framework).isNotNull();
        assertThat(framework.isDestroyed()).isFalse();
    }

    @Test
    void broadcasterFactoryIsAvailable() {
        assertThat(framework.getBroadcasterFactory()).isNotNull();
    }

    @Test
    void objectFactoryIsSpringAware() {
        assertThat(framework.objectFactory()).isInstanceOf(SpringAtmosphereObjectFactory.class);
    }

    @SpringBootApplication
    static class TestApp {
    }
}
