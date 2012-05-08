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
package org.atmosphere.transport.socketio;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionManager;
import org.atmosphere.protocol.socketio.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SocketIO implementation.
 * @author Sebastien Dionne
 */
public class SocketIOAtmosphereInterceptor implements AtmosphereInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(SocketIOAtmosphereInterceptor.class);
	
	public static final int BUFFER_SIZE_DEFAULT = 8192;
	
	private static SocketIOSessionManager sessionManager1 = null;
	private static Map<String, Transport> transports = new HashMap<String, Transport>();
	
	private static int heartbeatInterval = 15000;
	private static int timeout = 25000;
	private static int suspendTime = 20000;
	
	//private String availableTransports = "websocket,flashsocket,htmlfile,xhr-polling,jsonp-polling";
	//private String availableTransports = "websocket";
	private String availableTransports = "xhr-polling";
	 
	static {
		// VERSION 1
		org.atmosphere.protocol.socketio.protocol1.transport.WebSocketTransport websocketTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.WebSocketTransport();
		//org.atmosphere.protocol.socketio.protocol1.transport.FlashSocketTransport flashsocketTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.FlashSocketTransport();
		org.atmosphere.protocol.socketio.protocol1.transport.HTMLFileTransport htmlFileTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.HTMLFileTransport(BUFFER_SIZE_DEFAULT);
		org.atmosphere.protocol.socketio.protocol1.transport.XHRPollingTransport xhrPollingTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.XHRPollingTransport(BUFFER_SIZE_DEFAULT);
		org.atmosphere.protocol.socketio.protocol1.transport.JSONPPollingTransport jsonpPollingTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.JSONPPollingTransport(BUFFER_SIZE_DEFAULT);
		transports.put(websocketTransport1.getName()+ "-1", websocketTransport1);
		//transports.put(flashsocketTransport1.getName()+ "-1", flashsocketTransport1);
		transports.put(htmlFileTransport1.getName()+ "-1", htmlFileTransport1);
		transports.put(xhrPollingTransport1.getName()+ "-1", xhrPollingTransport1);
		transports.put(jsonpPollingTransport1.getName()+ "-1", jsonpPollingTransport1);
		
		for (Transport t: transports.values()) {
			t.init(null); // pas sur que c'est utile maintenant
		}
		
		sessionManager1 = new org.atmosphere.protocol.socketio.protocol1.transport.SocketIOSessionManagerImpl();
		sessionManager1.setTimeout(timeout);
		sessionManager1.setHeartbeatInterval(heartbeatInterval);
		sessionManager1.setRequestSuspendTime(suspendTime);
	}
	
	private SocketIOSessionManager getSessionManager(String version){
		
		if(version.equals("1")){
			return sessionManager1;
		}
		
		return null;
	}
	
    @Override
    public String toString() {
        return "SocketIO-Support";
    }

	@Override
	public Action inspect(AtmosphereResource r) {
		final AtmosphereRequest request = r.getRequest();
		final AtmosphereResponse response = r.getResponse();
		 
		final AtmosphereHandler atmosphereHandler = (AtmosphereHandler)request.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
		final AtmosphereResourceImpl resource = (AtmosphereResourceImpl)request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
		
		if(atmosphereHandler instanceof SocketIOAtmosphereHandler){
        	
			try {
	        	// on trouve le transport
	        	String path = request.getPathInfo();
	        	if (path == null || path.length() == 0 || "/".equals(path)) {
	        		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
	        		// TODO faut voir ce qu'on fait ici
	        		return null;
	        	}
	        	
	        	if (path.startsWith("/")){
	        		path = path.substring(1);
	        	} 
	        	
	        	// A VOIR SI C'EST PAS JUSTE POUR UN GET
	        	
	        	String[] parts = path.split("/");
	        	
	        	String protocol = null;
	        	String version = null;
	        	
	        	// ici on detecte la version du protocol.
	        	if(parts.length==0){
	        		return null;
	        	} else if(parts.length==1){
	        		
	        		// est-ce la version du protocol ?
	        		if(parts[0].length()==1){
	        			version = parts[0];
	        			//must be a digit
	        			if(!Character.isDigit(version.charAt(0))){
	        				version = null;
	        			}
	        		} else {
	        			protocol = parts[0];
	        		}
	        		
	        	} else {
	        		// un ex  :[1, xhr-polling, 7589995670715459]
	        		version = parts[0];
	        		protocol = parts[1];
	        		
	        		//must be a digit
	    			if(!Character.isDigit(version.charAt(0))){
	    				version = null;
	    				protocol = null;
	    			}
	        		
	        	}
	        	
	        	if(protocol==null && version==null){
	        		return null;
	        	} else if (protocol==null && version!=null){
	        		// nous avons un GET ou POST sans le protocol
	        		//response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
	        		
	        		response.setStatus(200);
	        		
	        		
	        		SocketIOSession session = getSessionManager(version).createSession(resource, (SocketIOAtmosphereHandler)atmosphereHandler);
	        		response.getWriter().print(session.getSessionId() + ":" + heartbeatInterval + ":" + timeout + ":" + availableTransports);
	        		
	        		//HACK pour le suspend dans JETTY
	        		request.setAttribute("HACK", Boolean.TRUE);
	        		
	        		return Action.CANCELLED;
	        	} else if(protocol!=null && version==null){
	        		version = "0";
	        	}
	        	
	        	Transport transport = transports.get(protocol + "-" + version);
	        	
	        	if(transport!=null){
	        		return transport.handle(null, resource, (SocketIOAtmosphereHandler)atmosphereHandler, getSessionManager(version));
	        	} else {
	        		logger.error("Protocol not supported : " + protocol);
	        	}
			} catch(Exception e){ 
				e.printStackTrace();
			}
        	
		}
		
		
		
		return Action.CANCELLED;
	}

	@Override
	public void configure(ServletConfig sc) {
		String s = sc.getInitParameter("socketio-transport");
		
		availableTransports = s;
		
	}
}
