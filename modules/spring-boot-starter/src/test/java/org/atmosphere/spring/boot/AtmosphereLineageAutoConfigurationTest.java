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

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageEntry;
import org.atmosphere.ai.lineage.LineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lineage is on by default: the AI auto-configuration installs a bounded,
 * content-redacting recorder, the admin serves what it holds, and the
 * install never replaces or removes a recorder the application chose.
 */
class AtmosphereLineageAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class,
                    AtmosphereAdminAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetHolder() {
        LineageRecorderHolder.reset();
    }

    @Test
    void onByDefaultWithoutPromptOrToolText() {
        contextRunner.run(context -> {
            assertThat(LineageRecorderHolder.get()).isInstanceOf(InMemoryLineageRecorder.class);
            var recorder = (InMemoryLineageRecorder) LineageRecorderHolder.get();
            assertThat(recorder.captureContent()).isFalse();
            assertThat(recorder.capacity()).isEqualTo(InMemoryLineageRecorder.DEFAULT_CAPACITY);

            recorder.record(entry());
            var lineage = context.getBean(AtmosphereAdmin.class).lineageController();
            var served = lineage.recent(10);
            assertThat(served).hasSize(1);
            assertThat(served.get(0)).containsEntry("userPrompt", "")
                    .containsEntry("agentId", "billing")
                    .containsEntry("attributes", Map.of("ai.agent.model", "billing-model"));
            assertThat(lineage.status()).containsEntry("recorder", "in-memory")
                    .containsEntry("captureContent", false);
        });
        assertThat(LineageRecorderHolder.get())
                .as("closing the context takes its recorder back out")
                .isSameAs(LineageRecorder.NOOP);
    }

    @Test
    void disabledLeavesTheHolderAtNoop() {
        contextRunner.withPropertyValues("atmosphere.ai.lineage.enabled=false")
                .run(context -> {
                    assertThat(LineageRecorderHolder.get()).isSameAs(LineageRecorder.NOOP);
                    assertThat(context.getBean(AtmosphereAdmin.class).lineageController().status())
                            .containsEntry("recorder", "none");
                });
    }

    @Test
    void captureContentAndCapacityAreHonoured() {
        contextRunner.withPropertyValues("atmosphere.ai.lineage.capture-content=true",
                        "atmosphere.ai.lineage.capacity=5")
                .run(context -> {
                    var recorder = (InMemoryLineageRecorder) LineageRecorderHolder.get();
                    assertThat(recorder.captureContent()).isTrue();
                    assertThat(recorder.capacity()).isEqualTo(5);
                    recorder.record(entry());
                    assertThat(recorder.recent(1).get(0).userPrompt()).isEqualTo("Was invoice 42 paid?");
                });
    }

    @Test
    void userRecorderBeanWins() {
        contextRunner.withUserConfiguration(UserRecorderConfig.class)
                .run(context -> assertThat(LineageRecorderHolder.get())
                        .isSameAs(context.getBean(LineageRecorder.class)));
    }

    @Test
    void aRecorderTheApplicationInstalledIsNeitherReplacedNorRemoved() {
        var applications = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(applications);

        contextRunner.run(context -> assertThat(LineageRecorderHolder.get()).isSameAs(applications));

        assertThat(LineageRecorderHolder.get()).isSameAs(applications);
    }

    private static LineageEntry entry() {
        return new LineageEntry("req-1", "alice", "billing", "conv-1", "sess-1", Instant.now(),
                "Was invoice 42 paid?",
                List.of(new LineageEntry.ToolCall("lookup_invoice", Instant.now(), "paid")),
                List.of(), Optional.empty(), Optional.empty(), "OK",
                Map.of("ai.agent.model", "billing-model"));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRecorderConfig {
        @Bean
        LineageRecorder auditLineageRecorder() {
            return entry -> { };
        }
    }
}
