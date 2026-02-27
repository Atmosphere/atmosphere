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
package org.atmosphere.ai.budget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TokenBudgetManagerTest {

    @Test
    public void testRecordUsageWithinBudget() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));

        assertTrue(manager.recordUsage("user1", 50));
        assertEquals(50, manager.currentUsage("user1"));
        assertEquals(50, manager.remaining("user1"));
    }

    @Test
    public void testRecordUsageExceedsBudget() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));

        assertTrue(manager.recordUsage("user1", 80));
        assertFalse(manager.recordUsage("user1", 30)); // 110 > 100
    }

    @Test
    public void testNoBudgetMeansNoLimit() {
        var manager = new TokenBudgetManager();
        assertTrue(manager.recordUsage("user1", 1_000_000));
        assertEquals(Long.MAX_VALUE, manager.remaining("user1"));
    }

    @Test
    public void testRecommendedModelBelowThreshold() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 1000, "cheap-model", 0.8));

        manager.recordUsage("user1", 500); // 50% — below threshold
        assertTrue(manager.recommendedModel("user1").isEmpty());
    }

    @Test
    public void testRecommendedModelAboveThreshold() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 1000, "cheap-model", 0.8));

        manager.recordUsage("user1", 850); // 85% — above threshold
        var model = manager.recommendedModel("user1");
        assertTrue(model.isPresent());
        assertEquals("cheap-model", model.get());
    }

    @Test
    public void testRecommendedModelThrowsWhenExhausted() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, "cheap-model", 0.8));

        manager.recordUsage("user1", 100);
        assertThrows(BudgetExceededException.class, () -> manager.recommendedModel("user1"));
    }

    @Test
    public void testBudgetExceededException() {
        var ex = new BudgetExceededException("user1", 100, 110);
        assertEquals("user1", ex.ownerId());
        assertEquals(100, ex.budget());
        assertEquals(110, ex.used());
        assertTrue(ex.getMessage().contains("user1"));
    }

    @Test
    public void testResetUsage() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));

        manager.recordUsage("user1", 80);
        assertEquals(80, manager.currentUsage("user1"));

        manager.resetUsage("user1");
        assertEquals(0, manager.currentUsage("user1"));
        assertEquals(100, manager.remaining("user1"));
    }

    @Test
    public void testRemoveBudget() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));
        manager.recordUsage("user1", 50);

        manager.removeBudget("user1");
        assertEquals(Long.MAX_VALUE, manager.remaining("user1"));
        assertEquals(0, manager.currentUsage("user1"));
    }

    @Test
    public void testNoFallbackModel() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));

        manager.recordUsage("user1", 90); // above threshold but no fallback
        assertTrue(manager.recommendedModel("user1").isEmpty());
    }

    @Test
    public void testBudgetValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new TokenBudgetManager.Budget("user1", -1, null, 0.8));
        assertThrows(IllegalArgumentException.class, () ->
                new TokenBudgetManager.Budget("user1", 100, null, 1.5));
    }

    @Test
    public void testMultipleOwners() {
        var manager = new TokenBudgetManager();
        manager.setBudget(new TokenBudgetManager.Budget("user1", 100, null, 0.8));
        manager.setBudget(new TokenBudgetManager.Budget("user2", 200, null, 0.8));

        manager.recordUsage("user1", 50);
        manager.recordUsage("user2", 150);

        assertEquals(50, manager.remaining("user1"));
        assertEquals(50, manager.remaining("user2"));
    }
}
