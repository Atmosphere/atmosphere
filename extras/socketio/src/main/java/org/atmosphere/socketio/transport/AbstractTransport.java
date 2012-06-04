/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.socketio.transport;

import org.atmosphere.cpr.AtmosphereRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.util.List;

/**
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public abstract class AbstractTransport implements Transport {
    private static final Logger logger = LoggerFactory.getLogger(XHRTransport.class);

    public static final String SESSION_KEY = AbstractTransport.class.getName() + ".Session";
    public static final String POST_MESSAGE_RECEIVED = "POST_MESSAGE_RECEIVED";

    protected String extractSessionId(AtmosphereRequest request) {
        String path = request.getPathInfo();

        if (path != null && path.length() > 0 && !"/".equals(path)) {
            if (path.startsWith("/"))
                path = path.substring(1);
            String[] parts = path.split("/");
            if (parts.length >= 2) {

                // will must validate that it's in the same URI
                String requestURI = request.getRequestURI();

                String protocol = parts[1];

                parts = requestURI.substring(requestURI.indexOf(protocol)).split("/");
                if (parts.length >= 2) {
                    return parts[1] == null ? null
                            : (parts[1].length() == 0 ? null : parts[1]);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Used to extract POST body from the request
     *
     * @param reader
     * @return
     */
    public static String extractString(Reader reader) {

        String output = null;
        try {
            Writer writer = new StringWriter();

            char[] buffer = new char[1024];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
            output = writer.toString();
        } catch (Exception e) {
        }
        return output;

    }

    @Override
    public void destroy() {
    }

    protected String decodePostData(String contentType, String data) {
        if (contentType == null || contentType.startsWith("application/x-www-form-urlencoded")) {
            if (data.length() > 2 && data.substring(0, 2).startsWith("d=")) {
                String extractedData = data.substring(3);
                try {
                    extractedData = URLDecoder.decode(extractedData, "UTF-8");
                    if (extractedData != null && extractedData.length() > 2) {
                        // trim and replace \" by "
                        if (extractedData.charAt(0) == '\"' && extractedData.charAt(extractedData.length() - 1) == '\"') {

                            extractedData = extractedData.substring(1, extractedData.length() - 1).replaceAll("\\\\\"", "\"");
                        }
                    }

                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                return extractedData;
            } else {
                return data;
            }
        } else if (contentType.startsWith("text/plain")) {
            return data;
        } else {
            return data;
        }
    }

    /**
     * Check if there is a disconnect message in the POST Body
     *
     * @param request
     * @return
     */
    protected boolean isDisconnectRequest(AtmosphereRequest request) {

        if ("GET".equals(request.getMethod())) {
            if (request.getParameterMap().containsKey("disconnect")) {
                return true;
            }
        } else if ("POST".equals(request.getMethod())) {
            try {
                String data = decodePostData(request.getContentType(), extractString(request.getReader()));
                request.setAttribute(POST_MESSAGE_RECEIVED, data);
                if (data != null && data.length() > 0) {
                    List<SocketIOPacketImpl> list = SocketIOPacketImpl.parse(data);
                    if (!list.isEmpty()) {
                        if (SocketIOPacketImpl.PacketType.DISCONNECT.equals(list.get(0).getFrameType())) {
                            return true;
                        }
                    }
                }
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
        return false;
    }
}
