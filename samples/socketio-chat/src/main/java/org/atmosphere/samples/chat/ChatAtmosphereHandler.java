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
package org.atmosphere.samples.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.jetty.util.ajax.JSON;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOFrame;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOEvent;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple SocketIOAtmosphereHandler that implement the logic to build a SocketIO Chat application.
 *
 * @author Sebastien Dionne
 */ 
public class ChatAtmosphereHandler implements SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> {

    private static final Logger logger = LoggerFactory.getLogger(ChatAtmosphereHandler.class);

    
    private static final ConcurrentMap<String, String> loggedUserMap = new ConcurrentSkipListMap<String, String>();
    
    
    /**
     * When the {@link AtmosphereServlet} detect an {@link HttpServletRequest}
     * maps to this {@link AtmosphereHandler}, the  {@link AtmosphereHandler#onRequest}
     * gets invoked and the response will be suspended depending on the http
     * method, e.g. GET will suspend the connection, POST will broadcast chat
     * message to suspended connection.
     *
     * @param event An {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    @SuppressWarnings("unused")
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {

        HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();

        //event.getBroadcaster().broadcast("bienvenue dans le chat");
        
        //res.getWriter().println("~e1~28~{\"welcome\":\"Welcome to Socket.IO Chat!\"}");
        //res.flushBuffer();
        
        if (!event.getResponse().getClass().isAssignableFrom(AtmosphereResponse.class)) {
            try {
            	event.getAtmosphereConfig().getServletContext()
                        .getNamedDispatcher(event.getAtmosphereConfig().getDispatcherName())
                        .forward(event.getRequest(), event.getResponse());
            } catch (ServletException e) {
                IOException ie = new IOException();
                ie.initCause(e);
                throw ie;
            }
        } else {
            upgrade(event);
        }
        
    }
    
    /**
     * WebSocket upgrade. This is usually inside that method that you decide if a connection
     * needs to be suspended or not. Override this method for specific operations like configuring their own
     * {@link Broadcaster}, {@link BroadcastFilter} , {@link BroadcasterCache} etc.
     *
     * @param resource an {@link AtmosphereResource}
     * @throws IOException
     */
    public void upgrade(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource) throws IOException {
        logger.debug("Suspending request: {}", resource.getRequest());
        resource.suspend(-1, false);
    }

    /**
     * Invoked when a call to {@link Broadcaster#broadcast(java.lang.Object)} is
     * issued or when the response times out, e.g whne the value
     * {@link AtmosphereResource#suspend(long)}
     * expires.
     *
     * @param event An {@link AtmosphereResourceEvent}
     * @throws java.io.IOException
     */
    @SuppressWarnings("unused")
    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
    	
    	HttpServletRequest req = event.getResource().getRequest();
        HttpServletResponse res = event.getResource().getResponse();
        
        SessionTransportHandler outbound = (org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler) req.getAttribute(SocketIOAtmosphereHandler.SessionTransportHandler);
    	
    	if(event.getMessage()!=null){
    		logger.error("onStateChange Event isResumedOnTimeout =" + event.isResumedOnTimeout() + " Event isResuming =" + event.isResuming() + " Event isSuspended =" + event.isSuspended() + " Message = " + event.getMessage().toString());
    	} else {
    		logger.error("onStateChange Message = null");
    		
    		if(event.isResumedOnTimeout()){
    			//res.getOutputStream().print("2::");
    			//res.flushBuffer();
    			
    			//debug websocket
    			if(outbound!=null){
    	        	try {
    	        		outbound.sendMessage("2::");
    	    		} catch (Exception e) {
    	    			outbound.disconnect();
    	    		}
    	        }
    			
    		} else {
    			// DEBUG ceci est un test pour le post du login
    			//res.getOutputStream().print("6:::1+[false]");
    			//res.flushBuffer();
    			System.out.println("Un resume a ete fait a cause d'un message recu sur la connection");
    		}
    		
    		return;
    	}
    	
    	// DEBUG.. ah.. pas de streaming donc le cas ne devrait pas se produire
    	if(event.isResuming()){
    		return ;
    	}
    	
    	Enumeration en = req.getAttributeNames();
    	while (en.hasMoreElements()) {
    		String name = (String) en.nextElement();
			System.out.println("Attribute name = " + name + "  : " + req.getAttribute(name));
		}
    	
        // rendu ici c'est que nous avons recu un broadcast pour NOUS, il 
        // ne concerne pas les autres
        
        if(outbound!=null){
        	try {
        		
        		List<SocketIOEvent> messages = SocketIOEvent.parse(event.getMessage().toString());
    			
    			for (SocketIOEvent msg: messages) {
    				switch(msg.getFrameType()){
    					case MESSAGE:
    					case JSON:
    					case EVENT:
    					case ACK:
    					case ERROR:
    						outbound.sendMessage(event.getMessage().toString());
    						break;
    					default:
    						logger.error("DEVRAIT PAS ARRIVER onStateChange SocketIOEvent msg = " + msg );
    				}
    			}
    		} catch (Exception e) {
    			outbound.disconnect();
    		}
        }
        
    }

    public void destroy() {
    }
    
    @SuppressWarnings("unused")
    public void onMessage(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, String data) throws IOException {
		logger.error("onMessage Data = " + data);
		
		HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();
		
        ServletOutputStream os = res.getOutputStream();
        
        SocketIOFrame frame = new SocketIOFrame(SocketIOFrame.FrameType.DATA, SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(Collections.singletonMap("message", data)));
        
        // broadcasting the message to others clients
        //SocketIOCometSupport.writeData(res, frame.encode());
        
        //os.println(frame.encode());
        //res.flushBuffer();
        
        //test de broadcast vers la connection GET
        //event.getBroadcaster().broadcast(frame.encode());
        
      //DEBUG en attendant que le broadcast fonctionne.. le probleme c'est que ca écrit
        // sur la connection POST et non GET.
        os.println(frame.encode());
        res.flushBuffer();
        
        
	}

	@SuppressWarnings("unused")
	public void onConnect(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SessionTransportHandler outbound) throws IOException {
		logger.error("onConnect");
		
		HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();
        
        /*
        try {
				outbound.sendMessage(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(Collections.singletonMap("welcome", "Welcome to Socket.IO Chat! Your SessionID is " + req.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID))));
			} catch (Exception e) {
				logger.error("onConnect Exception", e);
				outbound.disconnect();
			}
		
        //broadcast(event, SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(Collections.singletonMap("announcement", req.getSession().getId() + " connected")), outbound);
        broadcast(event, SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(Collections.singletonMap("announcement", req.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID) + " connected")), outbound);
        */
        
        // ca ne se rend jamais aux clients ???
        //event.getBroadcaster().broadcast(frame.encode());
        
        //SocketIOCometSupport.writeData(res,frame.encode());
	}
	
	private void broadcast(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, int messageType, String message, SessionTransportHandler outbound) {
		logger.error("Broadcasting: " + message);
		
		// DEBUG ceci ne semble pas se rendre aux clients. 
		//event.getBroadcaster().broadcast(new SocketIOFrame(SocketIOFrame.FrameType.DATA, SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(Collections.singletonMap("message", message))).encode());
		event.getBroadcaster().broadcast(new SocketIOFrame(SocketIOFrame.FrameType.DATA, messageType, message).encode());
		
		
		//sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.DATA, messageType, message));
		
		//DEBUG : CECI est un ECHO.. on ne devrait pas recevoir
		// les messages à cause du org.atmosphere.util.ExcludeSessionBroadcaster
		
		try {
			//outbound.sendMessage(messageType, message);
		} catch (Exception e) {
			e.printStackTrace();
			outbound.disconnect();
		}
		
	} 
	
	public void onDisconnect() throws IOException {
		logger.error("onDisconnect");
		
	}

	public void onOpen() throws IOException {
		logger.error("onOpen");
		
	}

	public void onClose() throws IOException {
		logger.error("onClose");
		
	}

	public void onError() throws IOException {
		logger.error("onError");
		
	}

	public void onRetry() throws IOException {
		logger.error("onRetry");
		
	}

	public void onReconnect() throws IOException {
		logger.error("onReconnect");
		
	}

	public void onDisconnect(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) {
		logger.error("onDisconnect");
		
	}
	
	@SuppressWarnings("unused")
	public void onMessage(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, int messageType, String message, SessionTransportHandler outbound) {
		logger.error("onMessage Message Received=" + message);
		
		HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();
		
        if(message==null || message.length()==0){
        	return;
        }
        
        //DEBUG
        try {
        	
        	// {"name":"nickname","args":["test"]}
        	// {"args":[{"user1":"user1","user2":"user2"}],"name":"nicknames"}
        	// {"args":[{"user1":"user1","user3":"user3","user2":"user2"}],"name":"nicknames"}
        	
        	// {"name":"user message","args":["user1 dit allo"]}
        	// {"args":["user1","user1 dit allo"],"name":"user message"}
        	
        	// {"args":["user2 connected"],"name":"announcement"}
        	
        	ObjectMapper mapper = new ObjectMapper();

        	ChatJSONObject chat = mapper.readValue(message, ChatJSONObject.class);
        	
        	if(ChatJSONObject.LOGIN.equalsIgnoreCase(chat.name)) {
        		
        		//debug pour java.lang.IllegalStateException: No SessionManager
        		try {
        			req.getSession().setAttribute("LOGINNAME", chat.getArgs().toArray()[0]);
        		} catch(Exception e){
        			e.printStackTrace();
        		}
        		
        		// est-il deja loggé ?
        		if(loggedUserMap.containsKey(chat.getArgs().toArray()[0])){
        			res.getOutputStream().print("6:::1+[true]");
        		} else {
        			
        			loggedUserMap.put((String)chat.getArgs().toArray()[0], (String)chat.getArgs().toArray()[0]);
        			
        			//res.getOutputStream().print("6:::1+[false]");
        			
        			//debug websocket
        			outbound.sendMessage("6:::1+[false]");
        		}
        		
    			res.flushBuffer();
    			
    			// on broadcast l'info aux autres usagers
    			event.getBroadcaster().broadcast("5:::{\"args\":[\"" + chat.getArgs().toArray()[0] + " connected\"],\"name\":\"announcement\"}");
    			
    	        // rendu ici c'est que nous avons recu un broadcast pour NOUS, il 
    	        // ne concerne pas les autres
    	        
    	        if(outbound!=null){
    	        	try {
    	        		
    	        		ChatJSONObject out = new ChatJSONObject();
    	        		
    	        		out.setName(ChatJSONObject.USERCONNECTEDLIST);
    	        		List list = new ArrayList();
    	        		
    	        		list.add(loggedUserMap);
    	        		
    	        		out.setArgs(list);
    	        		
    	        		
    	        		outbound.sendMessage("5:::" + mapper.writeValueAsString(out));
    	        		//5:::{"name":"nicknames","args":["user3"]}
    	        		//outbound.sendMessage("5:::{\"args\":[{\"user1\":\"user1\",\"user2\":\"user2\"}],\"name\":\"nicknames\"}");
    	    		} catch (Exception e) {
    	    			outbound.disconnect();
    	    		}
    	        }
    			
        	} else if(ChatJSONObject.USERCONNECTEDLIST.equalsIgnoreCase(chat.name)) {
        		res.getOutputStream().print("USERCONNECTEDLIST pas encore fait");
    			res.flushBuffer();
        	} else if(ChatJSONObject.MESSAGE.equalsIgnoreCase(chat.name)) {
        		
        		// je recois ceci  :{"name":"user message","args":["user1 dit allo"]}
        		
        		// et j'envoie ceci :{"args":["user1","user1 dit allo"],"name":"user message"}
        		
        		//debug pour java.lang.IllegalStateException: No SessionManager
        		String username = "user1";
        		try {
        			username = (String)req.getSession().getAttribute("LOGINNAME");
        		} catch(Exception e){
        			e.printStackTrace();
        		}
        		
        		List<String> msg = new ArrayList<String>();
        		msg.add(username);
        		msg.addAll(chat.args);
        		
        		ChatJSONObject out = new ChatJSONObject();
        		
        		out.setName(ChatJSONObject.MESSAGE);
        		out.setArgs(msg);
        		
        		
        		// on broadcast l'info aux autres usagers
        		event.getBroadcaster().broadcast("5:::" + mapper.writeValueAsString(out));
        		
    			//event.getBroadcaster().broadcast("5:::{\"args\":[\"" + username + " " + chat.getArgs().toArray()[0]  + "\"],\"name\":\"user message\"}");
        		
        	}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
	}
 
	public void onDisconnect(DisconnectReason reason, String message) {
		logger.error("onDisconnect DisconnectReason=" + reason + " message = " + message);
		
	}
	
}
