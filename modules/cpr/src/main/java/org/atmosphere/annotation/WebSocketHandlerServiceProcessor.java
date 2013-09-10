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
package org.atmosphere.annotation;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.interceptors;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(WebSocketHandlerService.class)
public class WebSocketHandlerServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandlerServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
        try {
            Class<WebSocketHandler> s = (Class<WebSocketHandler>) annotatedClass;
            WebSocketHandlerService m = s.getAnnotation(WebSocketHandlerService.class);

            framework.addAtmosphereHandler(m.path(), new AbstractReflectorAtmosphereHandler() {
                @Override
                public void onRequest(AtmosphereResource resource) throws IOException {
                }

                @Override
                public void destroy() {
                }
            }).initWebSocket();

            atmosphereConfig(m.atmosphereConfig(), framework);
            framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
            filters(m.broadcastFilters(), framework);

            interceptors(m.interceptors(), framework);

            AtmosphereInterceptor aa = listeners(m.listeners(), framework);
            if (aa != null) {
                framework.interceptor(aa);
            }

            WebSocketProcessor p = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
            p.registerWebSocketHandler(m.path(), s.newInstance());
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
