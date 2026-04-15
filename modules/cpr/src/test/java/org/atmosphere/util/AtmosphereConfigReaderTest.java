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

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphereConfigReaderTest {

    @Test
    void getInstanceReturnsSingleton() {
        AtmosphereConfigReader first = AtmosphereConfigReader.getInstance();
        AtmosphereConfigReader second = AtmosphereConfigReader.getInstance();

        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void parseInputStreamWithHandlerConfig() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/test"
                                        class-name="org.example.MyHandler"
                                        broadcaster="org.example.MyBroadcaster">
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        assertEquals(1, config.getAtmosphereHandlerConfig().size());
        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals("/test", handlerConfig.getContextRoot());
        assertEquals("org.example.MyHandler", handlerConfig.getClassName());
        assertEquals("org.example.MyBroadcaster", handlerConfig.getBroadcaster());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithMultipleHandlers() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/a" class-name="HandlerA"/>
                    <atmosphere-handler context-root="/b" class-name="HandlerB"/>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        assertEquals(2, config.getAtmosphereHandlerConfig().size());
        assertEquals("/a", config.getAtmosphereHandlerConfig().get(0).getContextRoot());
        assertEquals("/b", config.getAtmosphereHandlerConfig().get(1).getContextRoot());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithProperties() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/prop" class-name="PropHandler">
                        <property name="key1" value="val1"/>
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals(1, handlerConfig.getProperties().size());
        assertEquals("key1", handlerConfig.getProperties().get(0).getName());
        assertEquals("val1", handlerConfig.getProperties().get(0).getValue());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithApplicationConfig() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/app" class-name="AppHandler">
                        <applicationConfig>
                            <param-name>myParam</param-name>
                            <param-value>myValue</param-value>
                        </applicationConfig>
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals(1, handlerConfig.getApplicationConfig().size());
        assertEquals("myParam", handlerConfig.getApplicationConfig().get(0).getParamName());
        assertEquals("myValue", handlerConfig.getApplicationConfig().get(0).getParamValue());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithSupportSession() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/sess"
                                        class-name="SessHandler"
                                        support-session="true">
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals("true", handlerConfig.getSupportSession());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithBroadcastFilterClasses() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/filter"
                                        class-name="FilterHandler"
                                        broadcastFilterClasses="FilterA,FilterB">
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals(2, handlerConfig.getBroadcastFilterClasses().size());
        assertTrue(handlerConfig.getBroadcastFilterClasses().contains("FilterA"));
        assertTrue(handlerConfig.getBroadcastFilterClasses().contains("FilterB"));

        framework.destroy();
    }

    @Test
    void parseFileNotFoundThrowsException() {
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        assertThrows(RuntimeException.class,
                () -> AtmosphereConfigReader.getInstance().parse(config, "/nonexistent/file.xml"));

        framework.destroy();
    }

    @Test
    void parseInputStreamNoHandlers() throws FileNotFoundException {
        String xml = "<atmosphere-handlers></atmosphere-handlers>";
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        assertTrue(config.getAtmosphereHandlerConfig().isEmpty());

        framework.destroy();
    }

    @Test
    void parseInputStreamWithFrameworkConfig() throws FileNotFoundException {
        String xml = """
                <atmosphere-handlers>
                    <atmosphere-handler context-root="/fw" class-name="FwHandler">
                        <frameworkConfig>
                            <param-name>fwKey</param-name>
                            <param-value>fwVal</param-value>
                        </frameworkConfig>
                    </atmosphere-handler>
                </atmosphere-handlers>
                """;
        var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var framework = new AtmosphereFramework();
        var config = framework.getAtmosphereConfig();

        AtmosphereConfigReader.getInstance().parse(config, stream);

        var handlerConfig = config.getAtmosphereHandlerConfig().get(0);
        assertEquals(1, handlerConfig.getFrameworkConfig().size());
        assertEquals("fwKey", handlerConfig.getFrameworkConfig().get(0).getParamName());
        assertEquals("fwVal", handlerConfig.getFrameworkConfig().get(0).getParamValue());

        framework.destroy();
    }
}
