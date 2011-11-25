package org.atmosphere.samples.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOEvent;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple SocketIOAtmosphereHandler that implements the logic to build a SocketIO Chat application.
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
    	logger.error("onRequest");
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
    	logger.error("onStateChange event = " + event);
    	
    	HttpServletRequest req = event.getResource().getRequest();
        HttpServletResponse res = event.getResource().getResponse();
        
        SessionTransportHandler outbound = (org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler) req.getAttribute(SocketIOAtmosphereHandler.SessionTransportHandler);
    	
    	if(event.getMessage()!=null){
    		logger.error("onStateChange Event isResumedOnTimeout =" + event.isResumedOnTimeout() + " Event isResuming =" + event.isResuming() + " Event isSuspended =" + event.isSuspended() + " Message = " + event.getMessage().toString());
    	} else {
    		logger.error("onStateChange Message = null");
    		
    		if(event.isResumedOnTimeout()){
    			
    			if(outbound!=null){
    	        	try {
    	        		outbound.sendMessage("2::");
    	    		} catch (Exception e) {
    	    			outbound.disconnect();
    	    		}
    	        }
    			
    		} 
    		
    		return;
    	}
    	
    	if(event.isResuming()){
    		return ;
    	}
    	
        if(outbound!=null && event.getMessage().toString().length()>0){
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
    	logger.error("onConnect");
    }
    
	@SuppressWarnings("unused")
	public void onConnect(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SessionTransportHandler outbound) throws IOException {
		logger.error("onConnect");
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
	public void onMessage(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SessionTransportHandler outbound, String message) {
		logger.error("onMessage Message Received=" + message);
		
		HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();
		
        if(message==null || message.length()==0){
        	return;
        }
        
        try {
        	
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
        			outbound.sendMessage("6:::1+[true]");
        		} else {
        			loggedUserMap.put((String)chat.getArgs().toArray()[0], (String)chat.getArgs().toArray()[0]);
        			
        			outbound.sendMessage("6:::1+[false]");
        		}
        		
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
    	    		} catch (Exception e) {
    	    			outbound.disconnect();
    	    		}
    	        }
    			
        	} else if(ChatJSONObject.MESSAGE.equalsIgnoreCase(chat.name)) {
        		
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
