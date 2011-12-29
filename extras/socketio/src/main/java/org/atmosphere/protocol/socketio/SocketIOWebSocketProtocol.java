package org.atmosphere.protocol.socketio;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor.WebSocketException;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sebastien Dionne
 */
public class SocketIOWebSocketProtocol implements WebSocketProtocol {
    private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketProtocol.class);
    private AtmosphereResource<HttpServletRequest, HttpServletResponse> resource;
    
	@Override
	public boolean inspectResponse() {
		// TODO Auto-generated method stub
		return false;
	}

	/**
     * {@inheritDoc}
     */
    @Override
    public String handleResponse(AtmosphereResponse<?> res, String message) {
        // Should never be called
        return message;
    }

    @Override
    public byte[] handleResponse(AtmosphereResponse<?> res, byte[] message, int offset, int length) {
        // Should never be called
        return message;
    }

	@Override
	public void configure(AtmosphereConfig config) {
		// TODO Auto-generated method stub
		
	}

	/**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereRequest onMessage(WebSocket webSocket, String data){
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
    public AtmosphereRequest onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        logger.error("calling from " + this.getClass().getName() + " : " + "broadcast byte");
        
        String msg = null;
		try {
			msg = new String(data, offset, length, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
        
        //resource.getBroadcaster().broadcast(msg);
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        // eurk!!
        this.resource = (AtmosphereResource<HttpServletRequest, HttpServletResponse>) webSocket.resource();
    }

	@Override
	public void onClose(WebSocket webSocket) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onClose = " + webSocket.toString());
	}

	@Override
	public void onError(WebSocket webSocket, WebSocketException t) {
		logger.error(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
		
	}
}
