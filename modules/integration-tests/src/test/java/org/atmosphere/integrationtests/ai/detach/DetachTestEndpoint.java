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
package org.atmosphere.integrationtests.ai.detach;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An endpoint whose reply reaches the asker only, streamed by the built-in
 * runtime from the test's fake model, with an interceptor that reads the
 * request on the run path. The {@code @Prompt} method waits for
 * {@link #RELEASE} before it streams, so the test reloads the page before the
 * run's first frame.
 */
@AiEndpoint(path = DetachTestEndpoint.PATH, interceptors = RequestReadingInterceptor.class)
public class DetachTestEndpoint {

    public static final String PATH = "/atmosphere/detach";

    /** Released to let the {@code @Prompt} method stream its reply. */
    public static volatile CountDownLatch RELEASE = new CountDownLatch(1);

    @Prompt
    public void onPrompt(String message, StreamingSession session) throws InterruptedException {
        if (!RELEASE.await(30, TimeUnit.SECONDS)) {
            session.error(new IllegalStateException("never released"));
            return;
        }
        session.stream(message);
    }
}
