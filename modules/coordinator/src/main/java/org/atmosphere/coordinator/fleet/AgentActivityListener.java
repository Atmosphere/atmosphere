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
package org.atmosphere.coordinator.fleet;

/**
 * Callback for agent activity state transitions. Implementations receive
 * real-time notifications as agents move through their activity lifecycle
 * (thinking, executing, retrying, completed, etc.).
 *
 * <p>Discoverable via {@link java.util.ServiceLoader} or registered manually
 * during coordinator wiring.</p>
 *
 * @see AgentActivity
 * @see StreamingActivityListener
 */
@FunctionalInterface
public interface AgentActivityListener {

    /**
     * Called when an agent's activity state changes.
     *
     * @param activity the new activity state
     */
    void onActivity(AgentActivity activity);
}
