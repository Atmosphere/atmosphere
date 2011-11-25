/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory;

public class JSONPPollingTransport extends XHRTransport {
	public static final String TRANSPORT_NAME = "jsonp-polling";
	private long jsonpIndex = -1;

	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.JSONP["+ jsonpIndex +"]._('");
		}

		@Override
		protected void writeData(ServletResponse response, String data) throws IOException {
			response.getOutputStream().print(data);
		}

		protected void finishSend(ServletResponse response) throws IOException {
			response.getOutputStream().print("');");
			response.flushBuffer();
		}

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
	    	String path = request.getPathInfo();
	    	if (path.startsWith("/")) path = path.substring(1);
	    	String[] parts = path.split("/");
	    	if (parts.length >= 4) {
	    		jsonpIndex = Integer.parseInt(parts[3]);
	    	}
			startSend(response);
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, session.getSessionId()));
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + REQUEST_TIMEOUT));
		}
	}
	
	public JSONPPollingTransport(int bufferSize, int maxIdleTime) {
		super(bufferSize, maxIdleTime);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}
	

	protected XHRPollingSessionHelper createHelper(SocketIOSession session) {
		return new XHRPollingSessionHelper(session);
	}

	@Override
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, Factory sessionFactory) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}
}
