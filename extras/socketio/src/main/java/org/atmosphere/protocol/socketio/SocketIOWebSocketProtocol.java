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
package org.atmosphere.protocol.socketio;

import java.io.Serializable;
import java.util.List;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOWebSocketProtocol implements WebSocketProtocol, Serializable {
	private static final long serialVersionUID = 4015694886940858031L;
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketProtocol.class);
    private AtmosphereResource resource;
    
    /**
     * {@inheritDoc}
     */
	@Override
	public void configure(AtmosphereConfig config) {
		
	}
	
	/**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data){
        logger.error("calling from " + this.getClass().getName() + " : " + "broadcast String");
        
        //resource.getBroadcaster().broadcast(data);
        /*
        List<SocketIOEvent> messages = SocketIOEvent.parse(data);
		
        
        SocketIOWebSocketEventListener socketioEventListener = (SocketIOWebSocketEventListener)resource.getRequest().getAttribute(SocketIOCometSupport.SOCKETIOEVENTLISTENER); 
        
        if(socketioEventListener!=null){
        	SessionWrapper sessionWrapper = socketioEventListener.getSessionWrapper();
        			
        	SocketIOSession session = sessionWrapper.getSession();
	        
			for (SocketIOEvent msg: messages) {
				switch(msg.getFrameType()){
					case HEARTBEAT:
						session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), (String)null);
						break;
					default:
						resource.getBroadcaster().broadcast(data);
						break;
				}
			}
        } else {
        	resource.getBroadcaster().broadcast(data);
        }
        */
        
        return null;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        logger.error("calling from " + this.getClass().getName() + " : " + "broadcast byte");
        return onMessage(webSocket, new String(data, offset, length));
    }
	
    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(WebSocket webSocket) {
    	logger.error("calling from " + this.getClass().getName() + " : " + "onClose = " + webSocket.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
    	logger.error(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
    }

}
