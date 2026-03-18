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
package org.atmosphere.agui.runtime;

import org.atmosphere.protocol.ProtocolSession;

/**
 * AG-UI-specific protocol session that tracks the current run and thread IDs.
 * Stored on the {@link org.atmosphere.cpr.AtmosphereResource} via the
 * {@link #ATTRIBUTE_KEY} attribute.
 */
public final class AgUiSession extends ProtocolSession {

    /** Attribute key for storing this session on an AtmosphereResource. */
    public static final String ATTRIBUTE_KEY = "org.atmosphere.agui.session";

    private volatile String currentRunId;
    private volatile String currentThreadId;

    public AgUiSession() {
        super();
    }

    public String currentRunId() {
        return currentRunId;
    }

    public void setCurrentRunId(String runId) {
        this.currentRunId = runId;
    }

    public String currentThreadId() {
        return currentThreadId;
    }

    public void setCurrentThreadId(String threadId) {
        this.currentThreadId = threadId;
    }
}
