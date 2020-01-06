/*
 * Copyright 2008-2020 Async-IO.org
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

import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Factory for {@link WebSocketProcessor}.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessorFactory {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketProcessorFactory.class);

    private static WebSocketProcessorFactory factory;

    private final Map<AtmosphereFramework, WebSocketProcessor> processors = new WeakHashMap<AtmosphereFramework, WebSocketProcessor>();

    public final static synchronized WebSocketProcessorFactory getDefault() {
        if (factory == null) {
            factory = new WebSocketProcessorFactory();
        }
        return factory;
    }

    public Map<AtmosphereFramework, WebSocketProcessor> processors(){
        return processors;
    }

    /**
     * Return the {@link WebSocketProcessor}.
     *
     * @param framework {@link AtmosphereFramework}
     * @return an instance of {@link WebSocketProcessor}
     */
    public WebSocketProcessor getWebSocketProcessor(AtmosphereFramework framework) {
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

        String webSocketProcessorName = framework.getWebSocketProcessorClassName();
        if (!webSocketProcessorName.equalsIgnoreCase(DefaultWebSocketProcessor.class.getName())) {
            try {
                processor =  framework.newClassInstance(WebSocketProcessor.class,
                        (Class<WebSocketProcessor>) IOUtils.loadClass(getClass(), webSocketProcessorName));
            } catch (Exception ex) {
                logger.error("Unable to create {}", webSocketProcessorName);
                processor = new DefaultWebSocketProcessor();
            }
        }

        if (processor == null) {
            processor = new DefaultWebSocketProcessor();
        }
        processor.configure(framework.getAtmosphereConfig());

        return processor;
    }
}
