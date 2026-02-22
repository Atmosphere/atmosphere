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

import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereResponseImplTest {

    private AtmosphereFramework framework;

    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
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

    @Test
    public void singleHeaderTest() {
        framework.addAtmosphereHandler("/*", handler);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .header("header 1", "header 1 value")
                .header("header 2", null)
                .build();
        assertEquals(List.of("header 1 value"), response.getHeaders("header 1"));
    }

    @Test
    public void headerNullValueTest() {
        framework.addAtmosphereHandler("/*", handler);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .header("header 1", "header 1 value")
                .header("header 2", null)
                .build();
        assertEquals(Collections.singletonList(null), response.getHeaders("header 2"));
    }

    @Test
    public void missingHeaderTest() {
        framework.addAtmosphereHandler("/*", handler);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .header("header 1", "header 1 value")
                .header("header 2", null)
                .build();

        assertEquals(null, response.getHeaders("header 3"));
    }
}
