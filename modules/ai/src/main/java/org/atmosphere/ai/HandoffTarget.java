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

import org.atmosphere.cpr.AtmosphereResource;

/**
 * An AI handler that can take over a conversation through
 * {@link StreamingSession#handoff(String, String)}. The handoff is a server-side
 * call on the connection of the turn that hands off, so the message runs as a
 * prompt of that same connection; a broadcast on the target's path never runs
 * as a prompt.
 */
public interface HandoffTarget {

    /**
     * Runs {@code message} as a prompt of {@code resource} on this handler.
     *
     * @return {@code false} when nothing was started because the connection's
     * request can no longer be read
     */
    boolean acceptHandoff(AtmosphereResource resource, String message);
}
