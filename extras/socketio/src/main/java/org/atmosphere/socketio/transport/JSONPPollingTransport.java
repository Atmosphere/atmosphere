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

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.socketio.SocketIOSession;
import org.atmosphere.socketio.SocketIOSessionFactory;
import org.atmosphere.socketio.transport.SocketIOPacketImpl.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class JSONPPollingTransport extends XHRTransport {
	public static final String TRANSPORT_NAME = "jsonp-polling";
	
	private static final Logger logger = LoggerFactory.getLogger(JSONPPollingTransport.class);
	
	private long jsonpIndex = 0;

	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
		}

		@Override
		protected void writeData(HttpServletResponse response, String data) throws IOException {
			logger.trace("calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.j["+ jsonpIndex +"](\"" + data + "\");");
			
			logger.trace("WRITE SUCCESS calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
		}

		protected void finishSend(HttpServletResponse response) throws IOException {
			response.flushBuffer();
		}

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			
			if(request.getParameter("i")!=null){
				jsonpIndex = Integer.parseInt(request.getParameter("i"));
			} else {
				jsonpIndex = 0;
			}
			
	    	writeData(response, new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.CONNECT).toString());
		}
	}
	
	public JSONPPollingTransport(int bufferSize) {
		super(bufferSize);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}
	

	protected XHRPollingSessionHelper createHelper(SocketIOSession session) {
		return new XHRPollingSessionHelper(session);
	}

	@Override
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {
		
		if(session==null){
			session = sessionFactory.createSession(resource, atmosphereHandler);
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
			// for the Broadcaster
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_OUTBOUND, atmosphereHandler);
		}
		
		XHRPollingSessionHelper handler = createHelper(session);
		handler.connect(resource, atmosphereHandler);
		return session;
	}
	
	@Override
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}
}
