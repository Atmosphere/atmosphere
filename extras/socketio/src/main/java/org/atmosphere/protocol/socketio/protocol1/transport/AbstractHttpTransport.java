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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;

public abstract class AbstractHttpTransport extends AbstractTransport {
	/**
	 * This is a sane default based on the timeout values of various browsers.
	 */
	public static final long HTTP_REQUEST_TIMEOUT = 30 * 1000;

	/**
	 * The amount of time the session will wait before trying to send a ping. Using a value of half the HTTP_REQUEST_TIMEOUT should be good enough.
	 */
	public static final long HEARTBEAT_DELAY = HTTP_REQUEST_TIMEOUT / 2;

	/**
	 * This specifies how long to wait for a pong (ping response).
	 */
	public static final long HEARTBEAT_TIMEOUT = 10 * 1000;

	/**
	 * For non persistent connection transports, this is the amount of time to wait for messages before returning empty results.
	 */
	public static long REQUEST_TIMEOUT = 20 * 1000;

	protected static final String SESSION_KEY = "com.glines.socketio.server.AbstractHttpTransport.Session";

	public AbstractHttpTransport() {
	}

	protected abstract SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException;

	protected abstract SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException;
	
	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException {

		HttpServletRequest request = resource.getRequest();
		HttpServletResponse response = resource.getResponse();
		
		Object obj = request.getAttribute(SESSION_KEY);
		SocketIOSession session = null;
		String sessionId = null;
		if (obj != null) {
			session = (SocketIOSession) obj;
		} else {
			sessionId = extractSessionId(request);
			if (sessionId != null && sessionId.length() > 0) {
				session = sessionFactory.getSession(sessionId);
			}
		}

		if (session != null) {
			SessionTransportHandler handler = session.getTransportHandler();
			if (handler != null) {
				handler.handle(request, response, session);
			} else {
				// on fait un connect
				//DEBUG
				
				session = connect(session, resource, atmosphereHandler, sessionFactory);
				if (session == null) {
					response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				}
			}
		} else if (sessionId != null && sessionId.length() > 0) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		} else {
			if ("GET".equals(request.getMethod())) {
				session = connect(resource, atmosphereHandler, sessionFactory);
				if (session == null) {
					response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				}
			} else {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}
}
