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
import org.atmosphere.gwt.server.SerializationException;

/**
 * Modelled after OperaEventSourceResponseWriter
 *
 * @author p.havelaar
 */
public class WebsocketResponseWriter extends GwtResponseWriterImpl {
    
    private final static String MESSAGE_SEPERATOR = "#@@#";

    public WebsocketResponseWriter(GwtAtmosphereResourceImpl resource) {
        super(resource);
    }

    @Override
    public void initiate() throws IOException {
        // TODO : not sure what contentType to use
//		getResponse().setContentType("application/x-dom-event-stream");

        super.initiate();

        writer.append("c;c;")
                .append(String.valueOf(resource.getHeartBeatInterval())).append(';')
                .append(String.valueOf(connectionID)).append(MESSAGE_SEPERATOR);
    }

    @Override
    protected boolean supportsDeflate() {
        return false;
    }

    @Override
    protected void doSendError(int statusCode, String message) throws IOException {
//		getResponse().setContentType("application/x-dom-event-stream");
        writer.append("c;e;").append(String.valueOf(statusCode));
                
        if (message != null) {
            writer.append(";").append(message);
        }
        
        writer.append(MESSAGE_SEPERATOR);
    }

    @Override
    protected void doSuspend() throws IOException {
    }

    @Override
    protected void doWrite(List<? extends Serializable> messages) throws IOException, SerializationException {
        for (Serializable message : messages) {
            CharSequence string;
            char event;
            if (message instanceof CharSequence) {
                string = (CharSequence) message;
                event = 's';
            } else {
                string = serialize(message);
                event = 'o';
            }
            writer.append(event).append(";");
            writer.append(string).append(MESSAGE_SEPERATOR);
        }
    }

    @Override
    protected void doHeartbeat() throws IOException {
        writer.append("c;h").append(MESSAGE_SEPERATOR);
    }

    @Override
    public void doTerminate() throws IOException {
        writer.append("c;d").append(MESSAGE_SEPERATOR);
    }

}
