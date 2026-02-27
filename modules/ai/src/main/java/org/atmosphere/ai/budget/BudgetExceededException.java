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

/**
 * Thrown when a token budget has been exhausted.
 */
public class BudgetExceededException extends RuntimeException {

    private final String ownerId;
    private final long budget;
    private final long used;

    public BudgetExceededException(String ownerId, long budget, long used) {
        super("Token budget exceeded for " + ownerId + ": used " + used + " of " + budget);
        this.ownerId = ownerId;
        this.budget = budget;
        this.used = used;
    }

    public String ownerId() { return ownerId; }
    public long budget() { return budget; }
    public long used() { return used; }
}
