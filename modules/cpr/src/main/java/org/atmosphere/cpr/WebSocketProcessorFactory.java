/*
 * Copyright 2013 Jeanfrancois Arcand
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

import java.util.Map;
import java.util.WeakHashMap;

import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocketProcessor;

/**
 * Factory for {@link WebSocketProcessor}.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessorFactory {

    private static WebSocketProcessorFactory factory;

    private final Map<AtmosphereFramework, WebSocketProcessor> processors = new WeakHashMap<AtmosphereFramework, WebSocketProcessor>();

    public final static synchronized WebSocketProcessorFactory getDefault() {
        if (factory == null) {
            factory = new WebSocketProcessorFactory();
        }
        return factory;
    }

    /**
     * Return the {@link WebSocketProcessor}
     *
     * @param framework {@link AtmosphereFramework}
     * @return an instance of {@link WebSocketProcessor}
     */
    public WebSocketProcessor getWebSocketProcessor(
            AtmosphereFramework framework) {
        WebSocketProcessor processor = processors.get(framework);
        if (processor == null) {
            synchronized (framework) {
                processor = createProcessor(framework);
                processors.put(framework, processor);
            }
        }
        return processor;
    }

    public synchronized void destroy() {
        for (WebSocketProcessor processor : processors.values()) {
            processor.destroy();
        }
        processors.clear();
    }

    private WebSocketProcessor createProcessor(AtmosphereFramework framework) {
        WebSocketProcessor processor = null;

        String webSocketProcessorName = framework
                .getWebSocketProcessorClassName();
        if (!webSocketProcessorName
                .equalsIgnoreCase(DefaultWebSocketProcessor.class.getName())) {
            try {
                processor = (WebSocketProcessor) Thread.currentThread()
                        .getContextClassLoader()
                        .loadClass(webSocketProcessorName).newInstance();
            } catch (Exception ex) {
                try {
                    processor = (WebSocketProcessor) getClass()
                            .getClassLoader().loadClass(webSocketProcessorName)
                            .newInstance();
                } catch (Exception ex2) {
                }
            }
        }

        if (processor == null) {
            processor = new DefaultWebSocketProcessor(framework);
        }

        return processor;
    }
}
