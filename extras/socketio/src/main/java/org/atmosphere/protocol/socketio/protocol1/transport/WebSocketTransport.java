
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SessionWrapper;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOCometSupport;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOWebSocketEventListener;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketTransport extends AbstractTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketTransport.class);

	public static final String TRANSPORT_NAME = "websocket";
	public static final long CONNECTION_TIMEOUT = 10 * 1000;
	private final long maxIdleTime;

	public WebSocketTransport(int bufferSize, int maxIdleTime) {
		this.maxIdleTime = maxIdleTime;
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException {

		HttpServletRequest request = resource.getRequest();
		HttpServletResponse response = resource.getResponse();
		
		if(!processor.supportWebSocket()){
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TRANSPORT_NAME + " transport request");
			return;
		}
		
		String sessionId = extractSessionId(request);
		
		if ("GET".equals(request.getMethod()) && "WebSocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
			SocketIOSession session = sessionFactory.getSession(sessionId);
			
			request.setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
	        // on ajoute par default un websocketListener
	        SocketIOWebSocketEventListener socketioEventListener = new SocketIOWebSocketEventListener();
	        resource.addEventListener(socketioEventListener);
	        request.setAttribute(SocketIOCometSupport.SOCKETIOEVENTLISTENER, socketioEventListener);
			
	        SessionWrapperImpl sessionWrapper = new SessionWrapperImpl(session, socketioEventListener);
	        
	        socketioEventListener.setSessionWrapper(sessionWrapper);
	        
	        request.setAttribute(SocketIOAtmosphereHandler.SessionTransportHandler, sessionWrapper);
	        
			resource.suspend(-1, false);
			
		} else {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TRANSPORT_NAME + " transport request");
		}
	}
	
	public class SessionWrapperImpl implements SessionWrapper {
		public final SocketIOSession session;
		public final SocketIOWebSocketEventListener socketioEventListener;
		public boolean initiated = false;
		public WebSocket webSocket;
		
		SessionWrapperImpl(SocketIOSession session, SocketIOWebSocketEventListener socketioEventListener) {
			this.session = session;
			this.socketioEventListener = socketioEventListener;
			this.socketioEventListener.setSessionWrapper(this);
			
	        session.setHeartbeat(15000);
	        session.setTimeout(25000);
		}
		
        /*
           * (non-Javadoc)
           * @see org.eclipse.jetty.websocket.WebSocket#onDisconnect()
           */
		public void onDisconnect() {
			logger.error("calling from " + this.getClass().getName() + " : " + "onDisconnect");
			session.onShutdown();
		}
		
		/*
		 * (non-Javadoc)
		 * @see org.eclipse.jetty.websocket.WebSocket#onMessage(byte, java.lang.String)
		 */
		public void onMessage(byte frame, String message) {
			logger.error("calling from " + this.getClass().getName() + " : " + "onMessage");
			
			throw new RuntimeException("Devrait pas arriver");
		}

		/*
		 * (non-Javadoc)
		 * @see org.eclipse.jetty.websocket.WebSocket#onMessage(byte, byte[], int, int)
		 */
		public void onMessage(byte frame, byte[] data, int offset, int length) {
			logger.error("calling from " + this.getClass().getName() + " : " + "onMessage frame, data, offest, length");
            try
            {
                onMessage(frame,new String(data,offset,length,"UTF-8"));
            }
            catch(UnsupportedEncodingException e)
            {
            	// Do nothing for now.
            }
		}

		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOInbound.SocketIOOutbound#disconnect()
		 */
		@Override
		public void disconnect() {
			logger.error("calling from " + this.getClass().getName() + " : " + "disconnect");
			session.onDisconnect(DisconnectReason.DISCONNECT);
			try {
				webSocket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void close() {
			logger.error("calling from " + this.getClass().getName() + " : " + "close");
			session.startClose();
		}

		@Override
		public ConnectionState getConnectionState() {
			logger.error("calling from " + this.getClass().getName() + " : " + "getConncetionState");
			return session.getConnectionState();
		}

		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOInbound.SocketIOOutbound#sendMessage(java.lang.String)
		 */
		@Override
		public void sendMessage(String message) throws SocketIOException {
			logger.error("calling from " + this.getClass().getName() + " : " + "sendMessage(string)");
			//sendMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
			
			if(webSocket!=null){
				try {
					webSocket.write(message);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				logger.warn("WebSOCKET NULL");
			}
			
			
		}

		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOSession.SessionTransportHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, com.glines.socketio.SocketIOSession)
		 */
		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException {
			
			logger.error("calling from " + this.getClass().getName() + " : " + "handle");
			
    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unexpected request on upgraded WebSocket connection");
    		return;
		}

		@Override
		public void disconnectWhenEmpty() {
			logger.error("calling from " + this.getClass().getName() + " : " + "disconnectWhenEmpty");
			disconnect();
		}

		@Override
		public void abort() {
			logger.error("calling from " + this.getClass().getName() + " : " + "abort");
			try {
				webSocket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			webSocket = null;
			session.onShutdown();
		}

		@Override
		public SocketIOSession getSession() {
			return session;
		}

		@Override
		public boolean initiated() {
			return initiated;
		}

		@Override
		public WebSocket webSocket() {
			return webSocket;
		}

		@Override
		public void setWebSocket(WebSocket websocket) {
			this.webSocket = websocket;
		}

		@Override
		public void initiated(boolean initiated) {
			this.initiated = initiated;
		}

	}
}
