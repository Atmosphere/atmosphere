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
import org.atmosphere.protocol.socketio.protocol1.transport.XHRTransport.XHRSessionHelper;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONPPollingTransport extends XHRTransport {
	public static final String TRANSPORT_NAME = "jsonp-polling";
	
	private static final Logger logger = LoggerFactory.getLogger(JSONPPollingTransport.class);
	
	private long jsonpIndex = 0;

	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			/*
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.j["+ jsonpIndex +"](\"");
			*/
		}

		@Override
		protected void writeData(ServletResponse response, String data) throws IOException {
			//response.getOutputStream().print(data);
			logger.error("calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.j["+ jsonpIndex +"](\"" + data + "\");");
			
			logger.error("WRITE SUCCESS calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
		}

		protected void finishSend(ServletResponse response) throws IOException {
			//response.getOutputStream().print("\");");
			response.flushBuffer();
		}

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			
			/*  i=0   */
			
			if(request.getParameter("i")!=null){
				jsonpIndex = Integer.parseInt(request.getParameter("i"));
			} else {
				jsonpIndex = 0;
			}
			
			//startSend(response);
	    	
	    	writeData(response, "1::");
	    	
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
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory sessionFactory) throws IOException {
		
		if(session==null){
			session = sessionFactory.createSession(resource, atmosphereHandler);
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
			// pour le broadcast
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SessionTransportHandler, atmosphereHandler);
		}
		
		XHRPollingSessionHelper handler = createHelper(session);
		handler.connect(resource, atmosphereHandler);
		return session;
	}
	
	@Override
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}
}
