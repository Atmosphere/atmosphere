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
package org.atmosphere.session.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link RedisLongTermMemory} using Testcontainers.
 *
 * <p>Requires Docker. Tests are skipped if Docker is unavailable.</p>
 */
@Tag("redis")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RedisLongTermMemoryTest {

    private static final boolean DOCKER_AVAILABLE = isDockerAvailable();

    private GenericContainer<?> redis;
    private String redisUri;
    private RedisLongTermMemory memory;

    @SuppressWarnings("resource") // closed in tearDown()
    @BeforeAll
    public void setUp() {
        if (!DOCKER_AVAILABLE) {
            return;
        }
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        memory = new RedisLongTermMemory(redisUri);
    }

    @AfterEach
    public void cleanUp() {
        if (!DOCKER_AVAILABLE || memory == null) {
            return;
        }
        memory.clear("user-1");
        memory.clear("u1");
        memory.clear("u2");
        memory.clear("unknown");
    }

    @AfterAll
    public void tearDown() {
        if (memory != null) {
            memory.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    public void saveAndRetrieveFacts() {
        skipIfNoDocker();

        memory.saveFact("user-1", "Has a dog named Max");
        memory.saveFact("user-1", "Lives in Montreal");

        var facts = memory.getFacts("user-1", 10);
        assertEquals(2, facts.size());
        assertTrue(facts.contains("Has a dog named Max"));
        assertTrue(facts.contains("Lives in Montreal"));
    }

    @Test
    public void getFactsReturnsEmptyForUnknownUser() {
        skipIfNoDocker();
        assertEquals(List.of(), memory.getFacts("unknown", 10));
    }

    @Test
    public void maxFactsEvictsOldest() {
        skipIfNoDocker();

        var capped = new RedisLongTermMemory(redisUri, 3);
        try {
            capped.clear("u1");
            capped.saveFact("u1", "fact-1");
            capped.saveFact("u1", "fact-2");
            capped.saveFact("u1", "fact-3");
            capped.saveFact("u1", "fact-4");

            var facts = capped.getFacts("u1", 10);
            assertEquals(3, facts.size());
            assertFalse(facts.contains("fact-1"));
            assertTrue(facts.contains("fact-4"));
        } finally {
            capped.clear("u1");
            capped.close();
        }
    }

    @Test
    public void getFactsLimitsResults() {
        skipIfNoDocker();

        memory.saveFact("u1", "a");
        memory.saveFact("u1", "b");
        memory.saveFact("u1", "c");

        var facts = memory.getFacts("u1", 2);
        assertEquals(2, facts.size());
        assertTrue(facts.contains("b"));
        assertTrue(facts.contains("c"));
    }

    @Test
    public void clearRemovesAllFacts() {
        skipIfNoDocker();

        memory.saveFact("u1", "fact");
        memory.clear("u1");
        assertEquals(List.of(), memory.getFacts("u1", 10));
    }

    @Test
    public void saveBatchFacts() {
        skipIfNoDocker();

        memory.saveFacts("u1", List.of("a", "b", "c"));
        assertEquals(3, memory.getFacts("u1", 10).size());
    }

    @Test
    public void usersAreIsolated() {
        skipIfNoDocker();

        memory.saveFact("u1", "fact-u1");
        memory.saveFact("u2", "fact-u2");

        assertEquals(1, memory.getFacts("u1", 10).size());
        assertEquals("fact-u1", memory.getFacts("u1", 10).get(0));
        assertEquals("fact-u2", memory.getFacts("u2", 10).get(0));
    }

    private static void skipIfNoDocker() {
        if (!DOCKER_AVAILABLE) {
            Assumptions.abort("Docker not available");
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
