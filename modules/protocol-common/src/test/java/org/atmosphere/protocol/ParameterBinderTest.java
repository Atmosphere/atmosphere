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
package org.atmosphere.protocol;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterBinderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindArgumentsAsMapWithRequiredParams() throws Exception {
        var binder = new ParameterBinder(Set.of(), (t, topic) -> null);
        var params = List.of(
                new ParameterBinder.ParamInfo("name", "", true, String.class),
                new ParameterBinder.ParamInfo("count", "", true, int.class)
        );
        var args = mapper.readTree("{\"name\":\"test\",\"count\":42}");

        var result = binder.bindArgumentsAsMap(params, args);
        assertEquals("test", result.get("name"));
        assertEquals(42, result.get("count"));
    }

    @Test
    void bindArgumentsAsMapThrowsOnMissingRequired() throws Exception {
        var binder = new ParameterBinder(Set.of(), (t, topic) -> null);
        var params = List.of(
                new ParameterBinder.ParamInfo("name", "", true, String.class)
        );
        var args = mapper.readTree("{}");

        assertThrows(IllegalArgumentException.class,
                () -> binder.bindArgumentsAsMap(params, args));
    }

    @Test
    void bindArgumentsAsMapHandlesOptionalParams() throws Exception {
        var binder = new ParameterBinder(Set.of(), (t, topic) -> null);
        var params = List.of(
                new ParameterBinder.ParamInfo("name", "", false, String.class)
        );
        var args = mapper.readTree("{}");

        var result = binder.bindArgumentsAsMap(params, args);
        assertNull(result.get("name"));
    }

    @Test
    void convertParamHandlesPrimitives() throws Exception {
        var node = mapper.readTree("\"hello\"");
        assertEquals("hello", ParameterBinder.convertParam(node, String.class));

        node = mapper.readTree("42");
        assertEquals(42, ParameterBinder.convertParam(node, int.class));

        node = mapper.readTree("3.14");
        assertEquals(3.14, ParameterBinder.convertParam(node, double.class));

        node = mapper.readTree("true");
        assertEquals(true, ParameterBinder.convertParam(node, boolean.class));
    }

    @Test
    void defaultValueForPrimitives() {
        assertEquals(0, ParameterBinder.defaultValue(int.class));
        assertEquals(0L, ParameterBinder.defaultValue(long.class));
        assertEquals(0.0, ParameterBinder.defaultValue(double.class));
        assertEquals(0.0f, ParameterBinder.defaultValue(float.class));
        assertEquals(false, ParameterBinder.defaultValue(boolean.class));
        assertNull(ParameterBinder.defaultValue(String.class));
    }
}
