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
package org.atmosphere.integrationtests.ai.isolation;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records which connection each prompt ran on, and answers it with a reply
 * that names the prompt, so a test can tell whose {@code @Prompt} answered.
 */
@AiEndpoint(path = PromptIsolationTestEndpoint.PATH)
public class PromptIsolationTestEndpoint {

    public static final String PATH = "/atmosphere/prompt-isolation";

    /** {@code uuid + "|" + prompt} for every {@code @Prompt} invocation. */
    public static final List<String> INVOCATIONS = new CopyOnWriteArrayList<>();

    @Prompt
    public void onPrompt(String message, StreamingSession session, AtmosphereResource resource) {
        INVOCATIONS.add(resource.uuid() + "|" + message);
        session.send("reply-to:" + message);
        session.complete();
    }
}
