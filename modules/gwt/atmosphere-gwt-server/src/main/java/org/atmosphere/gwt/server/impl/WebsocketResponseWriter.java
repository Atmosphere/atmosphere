/*
 * Copyright 2011 Jeanfrancois Arcand
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

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * Modelled after OperaEventSourceResponseWriter
 * 
 * @author p.havelaar
 */
public class WebsocketResponseWriter extends GwtResponseWriterImpl {

    public WebsocketResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }

	@Override
	public void initiate() throws IOException {
        // TODO : not sure what contentType to use
//		getResponse().setContentType("application/x-dom-event-stream");

		super.initiate();

		writer.append("c\nc")
                .append(String.valueOf(resource.getHeartBeatInterval())).append(':')
                .append(String.valueOf(connectionID)).append("\n\n");
	}

	@Override
	protected void doSendError(int statusCode, String message) throws IOException {
//		getResponse().setContentType("application/x-dom-event-stream");
		writer.append("c\ne").append(String.valueOf(statusCode));
		if (message != null) {
			writer.append(' ').append(HTTPRequestResponseWriter.escape(message));
		}
		writer.append("\n\n");
	}

	@Override
	protected void doSuspend() throws IOException {
	}

	@Override
	protected void doWrite(List<? extends Serializable> messages) throws IOException {
		for (Serializable message : messages) {
			CharSequence string;
			char event;
			if (message instanceof CharSequence) {
				string = HTTPRequestResponseWriter.escape((CharSequence) message);
				event = 's';
			}
			else {
				string = serialize(message);
				event = 'o';
			}
			writer.append(event).append('\n');
			writer.append(string).append("\n\n");
		}
	}

	@Override
	protected void doHeartbeat() throws IOException {
		writer.append("c\nh\n\n");
	}

	@Override
	public void doTerminate() throws IOException {
		writer.append("c\nd\n\n");
	}

}
