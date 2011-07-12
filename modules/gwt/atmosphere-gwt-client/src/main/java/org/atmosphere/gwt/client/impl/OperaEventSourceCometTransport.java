
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
/*
 * Copyright 2009 Richard Zschech.
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
package org.atmosphere.gwt.client.impl;

import java.util.Collections;

import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereClientException;
import org.atmosphere.gwt.client.AtmosphereListener;

import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;

/**
 * This class uses Opera's event-source element to stream events.<br/>
 * http://my.opera.com/WebApplications/blog/show.dml/438711
 * 
 * The main issue with Opera's implementation is that we can't detect connection events. To support this three event
 * listeners are setup: one "s" for string messages, one "o" for the GWT serialized object messages, and the other "c"
 * for connection events. The server sends the event "c" as soon as the connection is established and "d"
 * when the connection is terminated. A connection timer is setup to detect initial connection errors. To detect
 * subsequent connection failure it also sends a heart beat events "h" when no messages have been sent for a specified
 * heart beat interval.
 * 
 * @author Richard Zschech
 */
public class OperaEventSourceCometTransport extends BaseCometTransport {
	
	private Element eventSource;
	private boolean connected;
	
	@Override
	public void initiate(AtmosphereClient client, AtmosphereListener listener) {
		super.initiate(client, listener);
		eventSource = createEventSource(this);
	}
	
	@Override
	public void connect(int connectionCount) {
		DOM.setElementAttribute(eventSource, "src", getUrl(connectionCount));
	}
	
	@Override
	public void disconnect() {
		DOM.setElementAttribute(eventSource, "src", "");
        super.disconnect();
		if (connected) {
			connected = false;
			listener.onDisconnected();
		}
	}
	
	private static native Element createEventSource(OperaEventSourceCometTransport client) /*-{
		var eventSource = document.createElement("event-source");

		var stringMessageHandler = $entry(function(event) {
			client.@org.atmosphere.gwt.client.impl.OperaEventSourceCometTransport::onString(Ljava/lang/String;)(event.data);
		});

		eventSource.addEventListener("s", stringMessageHandler, false);

		var objectMessageHandler = $entry(function(event) {
			client.@org.atmosphere.gwt.client.impl.OperaEventSourceCometTransport::onObject(Ljava/lang/String;)(event.data);
		});

		eventSource.addEventListener("o", objectMessageHandler, false);

		var connectionHandler = $entry(function(event) {
			client.@org.atmosphere.gwt.client.impl.OperaEventSourceCometTransport::onConnection(Ljava/lang/String;)(event.data);
		});

		eventSource.addEventListener("c", connectionHandler, false);

		return eventSource;
	}-*/;
	
	@SuppressWarnings("unused")
	private void onString(String message) {
		listener.onMessage(Collections.singletonList(HTTPRequestCometTransport.unescape(message)));
	}
	
	@SuppressWarnings("unused")
	private void onObject(String message) {
		try {
            listener.onMessage(Collections.singletonList(parse(message)));
        }
        catch (SerializationException e) {
            listener.onError(e, true);
        }
	}
	
	@SuppressWarnings("unused")
	private void onConnection(String message) {
		if (message.startsWith("c")) {
			connected = true;
			String initParameters = message.substring(1);
			try {
                String[] params = initParameters.split(":");
                connectionId = Integer.parseInt(params[1]);
				listener.onConnected(Integer.parseInt(params[0]), connectionId);
			}
			catch (NumberFormatException e) {
				listener.onError(new AtmosphereClientException("Unexpected init parameters: " + initParameters), true);
			}
		}
		else if (message.startsWith("e")) {
			disconnect();
			String status = message.substring(1);
			try {
				int statusCode;
				String statusMessage;
				int index = status.indexOf(' ');
				if (index == -1) {
					statusCode = Integer.parseInt(status);
					statusMessage = null;
				}
				else {
					statusCode = Integer.parseInt(status.substring(0, index));
					statusMessage = HTTPRequestCometTransport.unescape(status.substring(index + 1));
				}
				listener.onError(new StatusCodeException(statusCode, statusMessage), false);
			}
			catch (NumberFormatException e) {
				listener.onError(new AtmosphereClientException("Unexpected status code: " + status), false);
			}
		}
		else if (message.equals("d")) {
			connected = false;
			disconnect();
			listener.onDisconnected();
		}
		else if (message.equals("h")) {
			listener.onHeartbeat();
		}
		else {
			listener.onError(new AtmosphereClientException("Unexpected connection status: " + message), true);
		}
	}
}
