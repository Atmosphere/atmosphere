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
package org.atmosphere.ai;

/**
 * An injectable that the framework binds to the turn it is injected into: a
 * {@code @Prompt} method declaring its type receives {@link #forTurn} of the
 * registered instance, not the instance itself. A coordinator's
 * {@code AgentFleet} is one: the fleet a turn receives stops delegating once
 * that turn is cancelled.
 */
public interface TurnScoped {

    /**
     * The view of this injectable for one turn.
     *
     * @param session the turn's session
     * @return what the {@code @Prompt} method of that turn receives
     */
    Object forTurn(StreamingSession session);
}
