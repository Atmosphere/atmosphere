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
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.container;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Tomcat's WebSocket support. This code has been adapted from {@link org.apache.catalina.websocket.WebSocketServlet}
 */
public class Tomcat7BIOSupportWithWebSocket extends BlockingIOCometSupport implements TomcatWebSocketUtil.Delegate {

    private static final Logger logger = LoggerFactory.getLogger(Tomcat7BIOSupportWithWebSocket.class);
    private static final long serialVersionUID = 1L;
    private final WebSocketProcessor webSocketProcessor;

    public Tomcat7BIOSupportWithWebSocket(AtmosphereConfig config) {
        super(config);
        webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        return TomcatWebSocketUtil.doService(req, res, this, config, webSocketProcessor);
    }

    @Override
    public Action doService(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        return super.service(req, res);
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }
}

