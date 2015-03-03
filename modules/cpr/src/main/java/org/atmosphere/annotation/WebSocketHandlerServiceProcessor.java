/*
 * Copyright 2015 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.broadcasterClass;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(WebSocketHandlerService.class)
public class WebSocketHandlerServiceProcessor implements Processor<WebSocketHandler> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandlerServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<WebSocketHandler> annotatedClass) {
        try {
            WebSocketHandlerService m = annotatedClass.getAnnotation(WebSocketHandlerService.class);

            atmosphereConfig(m.atmosphereConfig(), framework);
            framework.addAtmosphereHandler(m.path(), AtmosphereFramework.REFLECTOR_ATMOSPHEREHANDLER).initWebSocket();

            framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
            filters(m.broadcastFilters(), framework);

            final LinkedList<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();

            AtmosphereInterceptor aa = listeners(m.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            for (Class i : m.interceptors()) {
                try {
                    l.add(framework.newClassInstance(AtmosphereInterceptor.class, i));
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }

            framework.setBroadcasterCacheClassName(m.broadcasterCache().getName());
            WebSocketProcessor p = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
            p.registerWebSocketHandler(m.path(), new WebSocketProcessor.WebSocketHandlerProxy(broadcasterClass(framework, m.broadcaster()),
                    framework.newClassInstance(WebSocketHandler.class, annotatedClass), l));
            framework.getAtmosphereConfig().startupHook(new AtmosphereConfig.StartupHook() {
                @Override
                public void started(AtmosphereFramework framework) {
                    framework.initHandlerInterceptors(l);
                }
            });
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }


}
