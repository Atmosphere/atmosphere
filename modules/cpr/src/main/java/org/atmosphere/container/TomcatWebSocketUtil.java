/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.apache.catalina.connector.RequestFacade;
import org.apache.catalina.websocket.StreamInbound;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TomcatWebSocketUtil {
    private static final Logger logger = LoggerFactory.getLogger(TomcatWebSocketUtil.class);
    private static final Queue<MessageDigest> sha1Helpers = new ConcurrentLinkedQueue<MessageDigest>();
    private static byte[] WS_ACCEPT;

    static {
        try {
            WS_ACCEPT = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("ISO_8859_1");
        } catch (Exception e) {
            logger.error("", e);
        }
    }


    public static Action doService(AtmosphereRequest req, AtmosphereResponse res,
                                                       Delegate delegate,
                                                       AtmosphereConfig config) throws IOException, ServletException {
        // First, handshake
        if (req.getAttribute(WebSocket.WEBSOCKET_SUSPEND) == null) {
            // Information required to send the server handshake message
            String key;
            String subProtocol = null;

            if (!headerContainsToken(req, "Upgrade", "websocket")) {
                return delegate.doService(req, res);
            }

            if (!headerContainsToken(req, "Connection", "upgrade")) {
                return delegate.doService(req, res);
            }

            if (!headerContainsToken(req, "sec-websocket-version", "13")) {
                logger.debug("WebSocket version not supported. Downgrading to Comet");
                res.sendError(501, "Websocket protocol not supported");
                return new Action(Action.TYPE.CANCELLED);
            }

            key = req.getHeader("Sec-WebSocket-Key");
            if (key == null) {
                return delegate.doService(req, res);
            }

            // If we got this far, all is good. Accept the connection.
            res.setHeader("Upgrade", "websocket");
            res.setHeader("Connection", "upgrade");
            res.setHeader("Sec-WebSocket-Accept", getWebSocketAccept(key));

            if (subProtocol != null) {
                res.setHeader("Sec-WebSocket-Protocol", subProtocol);
            }

            HttpServletRequest hsr = req.wrappedRequest();
            while (hsr instanceof HttpServletRequestWrapper)
                hsr = (HttpServletRequest) ((HttpServletRequestWrapper) hsr).getRequest();

            RequestFacade facade = (RequestFacade) hsr;
            StreamInbound inbound = new TomcatWebSocketHandler(AtmosphereRequest.loadInMemory(req, true),
                    config.framework(), config.framework().getWebSocketProtocol());
            facade.doUpgrade(inbound);
            return new Action(Action.TYPE.CREATED);
        }

        Action action = delegate.suspended(req, res);
        if (action.type() == Action.TYPE.SUSPEND) {
            logger.debug("Suspending resonse: {}", res);
        } else if (action.type() == Action.TYPE.RESUME) {
            logger.debug("Resume resonse: {}", res);
            req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
        }
        return action;
    }

    /*
    * This only works for tokens. Quoted strings need more sophisticated
    * parsing.
    */
    private static boolean headerContainsToken(HttpServletRequest req,
                                               String headerName, String target) {
        Enumeration<String> headers = req.getHeaders(headerName);
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            String[] tokens = header.split(",");
            for (String token : tokens) {
                if (target.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }


    /*
     * This only works for tokens. Quoted strings need more sophisticated
     * parsing.
     */
    private static List<String> getTokensFromHeader(HttpServletRequest req,
                                                    String headerName) {
        List<String> result = new ArrayList<String>();

        Enumeration<String> headers = req.getHeaders(headerName);
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            String[] tokens = header.split(",");
            for (String token : tokens) {
                result.add(token.trim());
            }
        }
        return result;
    }


    private static String getWebSocketAccept(String key) throws ServletException {

        MessageDigest sha1Helper = sha1Helpers.poll();
        if (sha1Helper == null) {
            try {
                sha1Helper = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                throw new ServletException(e);
            }
        }

        sha1Helper.reset();
        try {
            sha1Helper.update(key.getBytes("ISO_8859_1"));
        } catch (UnsupportedEncodingException e) {
            throw new ServletException(e);
        }
        String result = org.apache.catalina.util.Base64.encode(sha1Helper.digest(WS_ACCEPT));

        sha1Helpers.add(sha1Helper);

        return result;
    }

    public static interface Delegate {
        public Action doService(AtmosphereRequest req, AtmosphereResponse res)
                throws IOException, ServletException;

        public Action suspended(AtmosphereRequest request, AtmosphereResponse response)
                throws IOException, ServletException;

    }

}
