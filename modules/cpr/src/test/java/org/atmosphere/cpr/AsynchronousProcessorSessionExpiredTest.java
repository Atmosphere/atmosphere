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
package org.atmosphere.cpr;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.Enumeration;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A session that expires while a request is being processed is an expected
 * event: the connection is closed and the client is told why, without a
 * warning in the server log.
 */
class AsynchronousProcessorSessionExpiredTest {

    private AtmosphereFramework framework;
    private BlockingIOCometSupport asyncSupport;
    private ListAppender<ILoggingEvent> appender;
    private Level savedLevel;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        asyncSupport = new BlockingIOCometSupport(framework.getAtmosphereConfig());
        framework.setAsyncSupport(asyncSupport);
        framework.addAtmosphereHandler("/*", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) {
            }
        });
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "session-expired";
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
        });
        framework.getAtmosphereConfig().setSupportSession(true);

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AsynchronousProcessor.class);
        // logback-test.xml pins org.atmosphere at ERROR; open it up so that
        // both a WARN and a DEBUG event would be recorded.
        savedLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AsynchronousProcessor.class);
        logger.detachAppender(appender);
        logger.setLevel(savedLevel);
        framework.destroy();
    }

    @Test
    void expiredSessionClosesConnectionWithoutWarning() throws Exception {
        HttpSession session = mock(HttpSession.class);
        when(session.isNew()).thenReturn(true);
        doThrow(new IllegalStateException("Session already invalidated"))
                .when(session).setAttribute(anyString(), eq(""));
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder()
                .pathInfo("/session-expired")
                .session(session)
                .build();
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance(request);

        Action action = asyncSupport.suspended(request, response);

        assertEquals(Action.TYPE.CREATED, action.type());
        assertEquals(500, response.getStatus());
        assertEquals("Session expired", response.getHeader(X_ATMOSPHERE_ERROR));
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)),
                () -> "Unexpected warning: " + appender.list);
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().startsWith("Session Expired for")),
                () -> "Session expiry not logged at DEBUG: " + appender.list);
    }
}
