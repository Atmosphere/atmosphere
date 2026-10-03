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
package org.atmosphere.container;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A long-poll resumed before its container suspension took effect (in its
 * handler, or by another thread before the async context or latch existed)
 * completes at once instead of being held until its timeout, which is forever
 * for a suspend without one.
 */
class ResumeBeforeSuspendTest {

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    @Test
    void aLongPollResumedInItsHandlerIsNotSuspendedAgainByTheLifecycleInterceptor() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(servletConfig());
        framework.addAtmosphereHandler("/lp", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource r) {
                r.suspend();
                r.resume();
            }

            @Override
            public void destroy() {
            }
        }, List.<AtmosphereInterceptor>of(new AtmosphereResourceLifecycleInterceptor()));

        var request = new AtmosphereRequestImpl.Builder().pathInfo("/lp").method("GET")
                .headers(Map.of("X-Atmosphere-Transport", "long-polling")).build();
        var poll = Thread.ofVirtual().start(() -> {
            try {
                framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(poll.join(Duration.ofSeconds(5)),
                "a long-poll its handler already answered must complete, not be suspended without a timeout");
    }

    @Test
    void blockingIoReleasesASuspendWhoseResourceWasAlreadyResumed() {
        var support = new BlockingIOCometSupport(config());
        var request = mock(AtmosphereRequest.class);
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.isResumed()).thenReturn(true);
        when(request.resource()).thenReturn(resource);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> support.suspend(
                new Action(Action.TYPE.SUSPEND, -1), request, mock(AtmosphereResponse.class)));
    }

    @Test
    void servlet30CompletesAnAsyncContextStartedAfterTheResume() {
        var support = new Servlet30CometSupport(config());
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var async = mock(AsyncContext.class);
        when(request.startAsync(request, response)).thenReturn(async);
        when(request.getAttribute(FrameworkConfig.ASYNC_CONTEXT)).thenReturn(async);
        var resource = mock(AtmosphereResource.class);
        when(resource.isResumed()).thenReturn(true);
        when(request.resource()).thenReturn(resource);

        support.suspend(new Action(Action.TYPE.SUSPEND, -1), request, response);

        verify(async).complete();
    }

    @Test
    void servlet30LeavesASuspendedResourceSuspended() {
        var support = new Servlet30CometSupport(config());
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var async = mock(AsyncContext.class);
        when(request.startAsync(request, response)).thenReturn(async);
        when(request.getAttribute(FrameworkConfig.ASYNC_CONTEXT)).thenReturn(async);
        var resource = mock(AtmosphereResource.class);
        when(resource.isResumed()).thenReturn(false);
        when(request.resource()).thenReturn(resource);

        support.suspend(new Action(Action.TYPE.SUSPEND, 1_000), request, response);

        verify(async, never()).complete();
    }

    private static AtmosphereConfig config() {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> inv.getArgument(1));
        return config;
    }

    private static ServletConfig servletConfig() {
        return new ServletConfig() {
            @Override
            public String getServletName() {
                return "resume-before-suspend";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }
        };
    }
}
