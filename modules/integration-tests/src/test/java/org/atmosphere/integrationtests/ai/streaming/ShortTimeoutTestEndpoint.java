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
package org.atmosphere.integrationtests.ai.streaming;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;

/** An endpoint whose connections are suspended for {@link #TIMEOUT_MS}. */
@AiEndpoint(path = ShortTimeoutTestEndpoint.PATH, timeout = ShortTimeoutTestEndpoint.TIMEOUT_MS)
public class ShortTimeoutTestEndpoint {

    public static final String PATH = "/atmosphere/short-timeout";

    public static final long TIMEOUT_MS = 1_500;

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.send("reply-to:" + message);
        session.complete();
    }
}
