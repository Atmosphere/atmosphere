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
package org.atmosphere.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VoidServletConfigTest {

    @Test
    void defaultServletName() {
        var cfg = new VoidServletConfig();
        assertEquals("AtmosphereServlet", cfg.getServletName());
    }

    @Test
    void servletContextNotNull() {
        var cfg = new VoidServletConfig();
        assertNotNull(cfg.getServletContext());
    }

    @Test
    void defaultInitParamsEmpty() {
        var cfg = new VoidServletConfig();
        assertNull(cfg.getInitParameter("any"));
    }

    @Test
    void defaultInitParameterNamesEmpty() {
        var cfg = new VoidServletConfig();
        assertFalse(cfg.getInitParameterNames().hasMoreElements());
    }

    @Test
    void customInitParams() {
        var cfg = new VoidServletConfig(Map.of("key", "value"));
        assertEquals("value", cfg.getInitParameter("key"));
    }

    @Test
    void customInitParamsMissing() {
        var cfg = new VoidServletConfig(Map.of("key", "value"));
        assertNull(cfg.getInitParameter("other"));
    }

    @Test
    void atmosphereServletConstant() {
        assertEquals("AtmosphereServlet",
                VoidServletConfig.ATMOSPHERE_SERVLET);
    }
}
