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

import org.atmosphere.util.IOUtils;
import org.atmosphere.webtransport.WebTransportProcessor;
import org.atmosphere.webtransport.WebTransportProcessorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Factory for {@link WebTransportProcessor}. Mirrors
 * {@link WebSocketProcessorFactory} for the WebTransport SPI.
 *
 * @author Jeanfrancois Arcand
 */
public class WebTransportProcessorFactory {

    private static final Logger logger = LoggerFactory.getLogger(WebTransportProcessorFactory.class);

    private static final ReentrantLock factoryLock = new ReentrantLock();
    private static WebTransportProcessorFactory factory;

    private final ReentrantLock processorsLock = new ReentrantLock();
    private final Map<AtmosphereFramework, WebTransportProcessor> processors = new WeakHashMap<>();

    public static WebTransportProcessorFactory getDefault() {
        factoryLock.lock();
        try {
            if (factory == null) {
                factory = new WebTransportProcessorFactory();
            }
            return factory;
        } finally {
            factoryLock.unlock();
        }
    }

    public Map<AtmosphereFramework, WebTransportProcessor> processors() {
        return processors;
    }

    /**
     * Return or create the {@link WebTransportProcessor} for the given framework.
     *
     * @param framework the {@link AtmosphereFramework}
     * @return an instance of {@link WebTransportProcessor}
     */
    public WebTransportProcessor getWebTransportProcessor(AtmosphereFramework framework) {
        WebTransportProcessor processor = processors.get(framework);
        if (processor == null) {
            processorsLock.lock();
            try {
                processor = processors.get(framework);
                if (processor == null) {
                    processor = createProcessor(framework);
                    processors.put(framework, processor);
                }
            } finally {
                processorsLock.unlock();
            }
        }
        return processor;
    }

    public void destroy() {
        processorsLock.lock();
        try {
            for (WebTransportProcessor processor : processors.values()) {
                processor.destroy();
            }
            processors.clear();
        } finally {
            processorsLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private WebTransportProcessor createProcessor(AtmosphereFramework framework) {
        WebTransportProcessor processor = null;

        String processorName = framework.getAtmosphereConfig()
                .getInitParameter(ApplicationConfig.WEBTRANSPORT_PROCESSOR);
        if (processorName != null && !processorName.isEmpty()) {
            try {
                processor = framework.newClassInstance(WebTransportProcessor.class,
                        (Class<WebTransportProcessor>) IOUtils.loadClass(getClass(), processorName));
            } catch (Exception ex) {
                logger.error("Unable to create WebTransportProcessor {}", processorName, ex);
            }
        }

        if (processor == null) {
            processor = new WebTransportProcessorAdapter();
        }
        processor.configure(framework.getAtmosphereConfig());

        return processor;
    }
}
