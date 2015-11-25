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
package org.atmosphere.container;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * websocketd support.
 *
 * @author Jeanfrancois Arcand
 */
public class EmbeddedWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedWebSocketHandler.class);

    private final AtmosphereFramework framework;
    private boolean on;
    private WebSocketProcessor processor;
    private final ConcurrentHashMap<InputStream, WebSocket> webSockets = new ConcurrentHashMap<InputStream, WebSocket>();
    private String requestURI = "/";

    public EmbeddedWebSocketHandler(AtmosphereFramework framework) {
        this.framework = framework;
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()) {
            public boolean supportWebSocket() {
                return true;
            }
        }).getAtmosphereConfig().startupHook(new AtmosphereConfig.StartupHook() {
            @Override
            public void started(AtmosphereFramework framework) {
                if (framework.getAtmosphereConfig().handlers().isEmpty()) {
                    framework.addAtmosphereHandler("/*", ECHO_ATMOSPHEREHANDLER);
                }
            }
        });
    }

    public EmbeddedWebSocketHandler on() {
        if (!on) {
            on = true;
            framework.init();
            processor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
        }
        logger.info("EmbeddedWebSocketHandler started");
        return this;
    }

    public EmbeddedWebSocketHandler off() {
        if (on) framework.destroy();
        return this;
    }


    public EmbeddedWebSocketHandler serve(InputStream inputStream) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        String a = "";

        while (!(a.equals("===quit"))) {
            a = br.readLine();
            logger.info("Received WebSocket Message {}", a);
            processor.invokeWebSocketProtocol(webSocket(inputStream), a);
        }
        return this;
    }

    private WebSocket webSocket(InputStream inputStream) throws IOException {
        WebSocket webSocket = webSockets.get(inputStream);
        if (webSocket == null) {
            webSocket = new ArrayBaseWebSocket();
            webSockets.put(inputStream, webSocket);
            AtmosphereRequest request = AtmosphereRequestImpl.newInstance()
                    .header("Connection", "Upgrade")
                    .header("Upgrade", "websocket")
                    .pathInfo(requestURI);
            try {
                processor.open(webSocket, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, webSocket));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return webSocket;
    }

    public EmbeddedWebSocketHandler requestURI(String requestURI) {
        this.requestURI = requestURI;
        return this;
    }

    private final class ArrayBaseWebSocket extends WebSocket {

        public ArrayBaseWebSocket() {
            super(framework.getAtmosphereConfig());
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) throws IOException {
            System.out.println(s);
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) throws IOException {
            System.out.println(new String(b, offset, length));
            return this;
        }

        @Override
        public void close() {
        }
    }

    public static void main(String... args) throws IOException {
        new EmbeddedWebSocketHandler(new AtmosphereFramework()).on().serve(System.in);
    }

    public static AtmosphereHandler ECHO_ATMOSPHEREHANDLER = new AbstractReflectorAtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            String body = IOUtils.readEntirelyAsString(resource).toString();
            if (!body.isEmpty()) {
                resource.getBroadcaster().broadcast(body);
            }
        }
    };
}
