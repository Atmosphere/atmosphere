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
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.inject.AtmosphereConfigInjectable;
import org.atmosphere.inject.InjectableObjectFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class AtmosphereRuntimeHintsTest {

    private RuntimeHints hints;

    @BeforeEach
    void setUp() {
        hints = new RuntimeHints();
        new AtmosphereRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void coreFrameworkClassesHaveReflectionHints() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(AtmosphereFramework.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(DefaultBroadcaster.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(DefaultBroadcasterFactory.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(AtmosphereResourceImpl.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void injectableSpiClassesHaveReflectionHints() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(AtmosphereConfigInjectable.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(InjectableObjectFactory.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void springObjectFactoryHasReflectionHints() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(SpringAtmosphereObjectFactory.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void annotationProcessorsHaveReflectionHints() {
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(
                        "org.atmosphere.annotation.ManagedServiceProcessor"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(
                        "org.atmosphere.annotation.AtmosphereHandlerServiceProcessor"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(
                        "org.atmosphere.annotation.WebSocketHandlerServiceProcessor"))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void serviceLoaderResourcesAreRegistered() {
        assertThat(RuntimeHintsPredicates.resource()
                .forResource("META-INF/services/org.atmosphere.inject.Injectable"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource()
                .forResource("META-INF/services/org.atmosphere.inject.CDIProducer"))
                .accepts(hints);
    }
}
