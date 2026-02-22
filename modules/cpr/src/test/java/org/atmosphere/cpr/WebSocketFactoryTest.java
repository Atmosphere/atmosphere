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

import org.atmosphere.config.service.WebSocketFactoryService;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketFactory;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Optional;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class WebSocketFactoryTest {

    private AtmosphereFramework framework;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.addAnnotationPackage(WebSocketFactoryTest.class);
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
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
                return null;
            }
        });
    }

    @AfterEach
    public void destroy(){
        framework.destroy();
    }

    @WebSocketFactoryService
    public final static class CustomFactory implements WebSocketFactory {

        @Override
        public WebSocket find(String uuid) {
            return null;
        }

        @Override
        public Optional<WebSocket> findWebSocket(String uuid) {
            return Optional.empty();
        }
    }

    @Test
    public void testAnnotation() throws IOException, ServletException {
        assertNotNull(framework.webSocketFactory());
        assertEquals(framework.webSocketFactory().getClass(), CustomFactory.class);
    }

}
