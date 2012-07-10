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

package org.atmosphere.gwt.server.impl;


import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * @author p.havelaar
 */
abstract public class StreamingProtocolResponseWriter extends ManagedStreamResponseWriter {

    private static final int MAX_PADDING_REQUIRED = 2048;
    private static final String PADDING_STRING;

    static {
        char[] padding = new char[MAX_PADDING_REQUIRED + 1];
        for (int i = 0; i < padding.length - 1; i++) {
            padding[i] = '*';
        }
        padding[padding.length - 1] = '\n';
        PADDING_STRING = new String(padding);
    }

    public StreamingProtocolResponseWriter(GwtAtmosphereResourceImpl resource) {
        super(resource);

    }

    abstract String getContentType();

    @Override
    public void initiate() throws IOException {
        getResponse().setContentType(getContentType());

        String origin = getRequest().getHeader("Origin");
        if (origin != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Origin: " + origin);
            }
            getResponse().setHeader("Access-Control-Allow-Origin", origin);
        }

        super.initiate();

        // send connection parameters to client
        writer.append('!').append(String.valueOf(resource.getHeartBeatInterval())).append(':');
        writer.append(String.valueOf(connectionID)).append('\n');
    }


    static CharSequence escape(CharSequence string) {
        int length = (string != null) ? string.length() : 0;
        int i = 0;
        loop:
        while (i < length) {
            char ch = string.charAt(i);
            switch (ch) {
                case '\\':
                case '\n':
                    break loop;
            }
            i++;
        }

        if (i == length)
            return string;

        StringBuilder str = new StringBuilder(string.length() * 2);
        str.append(string, 0, i);
        while (i < length) {
            char ch = string.charAt(i);
            switch (ch) {
                case '\\':
                    str.append("\\\\");
                    break;
                case '\n':
                    str.append("\\n");
                    break;
                default:
                    str.append(ch);
            }
            i++;
        }
        return str;
    }

    @Override
    protected CharSequence getPadding(int padding) {
        if (padding > PADDING_STRING.length() - 1) {
            StringBuilder result = new StringBuilder(padding);
            while (padding > 0) {
                if (padding > PADDING_STRING.length() - 1) {
                    result.append(PADDING_STRING);
                    padding -= PADDING_STRING.length() - 1;
                } else {
                    result.append(PADDING_STRING.substring(PADDING_STRING.length() - 1 - padding));
                    padding = 0;
                }
            }
            return result.toString();
        } else {
            return PADDING_STRING.substring(PADDING_STRING.length() - padding - 1);
        }
    }

    @Override
    protected void doSendError(int statusCode, String message) throws IOException {
        getResponse().setStatus(statusCode);
        if (message != null) {
            writer.append(message);
        }
    }

    @Override
    protected void doWrite(List<? extends Serializable> messages) throws IOException {
        for (Serializable message : messages) {
            CharSequence string;
            if (message instanceof CharSequence) {
                string = escape((CharSequence) message);
                if (string == message) {
                    writer.append('|');
                } else {
                    writer.append(']');
                }
            } else {
                string = serialize(message);
            }

            writer.append(string).append('\n');
        }
    }

    @Override
    protected boolean isOverRefreshLength(int written) {
        if (length != null) {
            return written > length;
        } else {
            return written > 5 * 1024 * 1024;
        }
    }

    @Override
    protected void doHeartbeat() throws IOException {
        writer.append("#\n");
    }

    @Override
    protected void doTerminate() throws IOException {
        writer.append("?\n");
    }

    @Override
    protected void doRefresh() throws IOException {
        writer.append("@\n");
    }

}
