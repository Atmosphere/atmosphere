/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.gwt.client.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereClientException;
import org.atmosphere.gwt.client.AtmosphereGWTSerializer;

import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;

/**
 *
 * @author p.havelaar
 */
abstract public class StreamingProtocolTransport extends BaseCometTransport {
	
	private static final String SEPARATOR = "\n";
    
	protected boolean aborted;
    protected boolean expectingDisconnection;
	protected int read;

    public void init() {
        aborted = false;
		expectingDisconnection = false;
		read = 0;
    }
        
	protected void onReceiving(int statusCode, String responseText, boolean connected) {
		if (statusCode != Response.SC_OK) {
			if (!connected) {
				expectingDisconnection = true;
				listener.onError(new StatusCodeException(statusCode, responseText), connected);
			}
		}
		else {
			int index = responseText.lastIndexOf(SEPARATOR);
			if (index > read) {
				List<Serializable> messages = new ArrayList<Serializable>();
				JsArrayString data = AtmosphereClient.split(responseText.substring(read, index), SEPARATOR);
				int length = data.length();
				for (int i = 0; i < length; i++) {
					if (aborted) {
						return;
					}
					parse(data.get(i), messages);
				}
				read = index + 1;
				if (!messages.isEmpty()) {
					listener.onMessage(messages);
				}
			}
			
			if (!connected) {
				if (expectingDisconnection) {
					listener.onDisconnected();
				}
				else {
					listener.onError(new AtmosphereClientException("Unexpected disconnection"), false);
				}
			}
		}
	}

	private void parse(String message, List<Serializable> messages) {
		if (expectingDisconnection) {
			listener.onError(new AtmosphereClientException("Expecting disconnection but received message: " + message), true);
		}
		else if (message.isEmpty()) {
			listener.onError(new AtmosphereClientException("Invalid empty message received"), true);
		}
		else {
			char c = message.charAt(0);
			switch (c) {
			case '!':
				String initParameters = message.substring(1);
				try {
                    String[] params = initParameters.split(":");
                    connectionId = Integer.parseInt(params[1]);
					listener.onConnected(Integer.parseInt(params[0]), connectionId);
				}
				catch (NumberFormatException e) {
					listener.onError(new AtmosphereClientException("Unexpected init parameters: " + initParameters), true);
				}
				break;
			case '?':
				// clean disconnection
				expectingDisconnection = true;
				break;
			case '#':
				listener.onHeartbeat();
				break;
			case '@':
				listener.onRefresh();
				break;
			case '*':
				// ignore padding
				break;
			case '|':
				messages.add(message.substring(1));
				break;
			case ']':
				messages.add(unescape(message.substring(1)));
				break;
			case '[':
			case 'R':
			case 'r':
			case 'f':
                try {
                    messages.add(parse(message));
                }
                catch (SerializationException e) {
                    listener.onError(e, true);
                }
				break;
			default:
				listener.onError(new AtmosphereClientException("Invalid message received: " + message), true);
			}
		}
	}
    
	static String unescape(String string) {
		return string.replace("\\n", "\n").replace("\\\\", "\\");
	}
}
