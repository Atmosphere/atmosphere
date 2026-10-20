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

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads the request on the run path, as an application interceptor does (the
 * ai-tools sample's cost metering): {@code preProcess} stamps an attribute on
 * it and {@code beforeCompletion} reads it back, by which time the page that
 * asked may have reloaded. Each read is recorded: the attribute's value, or
 * the exception the read threw.
 */
public class RequestReadingInterceptor implements AiInterceptor {

    static final String ATTRIBUTE = "detach.marker";

    /** What each {@code beforeCompletion} read. */
    public static final List<String> READS = new CopyOnWriteArrayList<>();

    /** A prompt this interceptor refuses, as an admission policy refuses a turn. */
    static final String DENIED = "deny this turn";
    static final String DENIAL = "Denied by the test's admission policy";

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        if (DENIED.equals(request.message())) {
            throw new org.atmosphere.ai.RequestDeniedException(DENIAL);
        }
        resource.getRequest().setAttribute(ATTRIBUTE, "stamped:" + request.message());
        return request;
    }

    @Override
    public void beforeCompletion(AiRequest request, StreamingSession session, AtmosphereResource resource) {
        try {
            READS.add(String.valueOf(resource.getRequest().getAttribute(ATTRIBUTE)));
        } catch (RuntimeException e) {
            READS.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
