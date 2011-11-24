package org.atmosphere.protocol.socketio;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOEvent;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketIOWebSocketEventListener implements WebSocketEventListener {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketEventListener.class);
	private SessionWrapper sessionWrapper = null;
	
	
	public SocketIOWebSocketEventListener(){
		logger.error("SocketIOWebSocketEventListener CONSTRUCTEUR");
	}
	
	public void setSessionWrapper(SessionWrapper sessionWrapper){
		this.sessionWrapper = sessionWrapper;
	}
	
	public SessionWrapper getSessionWrapper(){
		return sessionWrapper;
	}
	
	@Override
	public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onThrowable event = " + event);
		
	}
	
	@Override
	public void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onSuspend event = " + event);
		
	}
	
	@Override
	public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onResume event = " + event);
		
	}
	
	@Override
	public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onDisconnect event = " + event);
		sessionWrapper.onDisconnect();
	}
	
	@Override
	public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onBroadcast event = " + event);
		
	}
	
	@Override
	public void onMessage(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onMessage event = " + event);
		
		if (!sessionWrapper.initiated()) {
			if ("OPEN".equals(event.message())) {
				try {
					//event.webSocket().write((byte)0x8, SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, sessionWrapper.getSession().getSessionId()));
					//event.webSocket().write((byte)0x8, SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + sessionWrapper.getSession().getHeartbeat()));
					sessionWrapper.getSession().onConnect(sessionWrapper.getSession().getAtmosphereResourceImpl(), sessionWrapper);
					sessionWrapper.initiated(true);
				} catch (Exception e) {
					e.printStackTrace();
					try {
						sessionWrapper.webSocket().close();
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					sessionWrapper.getSession().onShutdown();
				}
			} else {
				try {
					sessionWrapper.webSocket().close();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				sessionWrapper.getSession().onShutdown();
			}
		} else {
			List<SocketIOEvent> messages = SocketIOEvent.parse(event.message());
			
			SocketIOSession session = sessionWrapper.getSession();
			for (SocketIOEvent msg: messages) {
				//sessionWrapper.getSession().onMessage(sessionWrapper.getSession().getAtmosphereResourceImpl(), sessionWrapper, msg);
				session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg.getData());
			}
		}
	}
	
	@Override
	public void onHandshake(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onHandshake event = " + event);
		
	}
	
	@Override
	public void onDisconnect(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onDisconnect event = " + event);
		
	}
	
	@Override
	public void onControl(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onControl event = " + event);
		
	}
	
	@Override
	public void onConnect(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onConnect event = " + event);
		
		sessionWrapper.setWebSocket(event.webSocket());
		
		
		try {
			event.webSocket().write("1::");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			//event.webSocket().write((byte)0x8, SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, sessionWrapper.getSession().getSessionId()));
			//event.webSocket().write((byte)0x8, SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + sessionWrapper.getSession().getHeartbeat()));
			sessionWrapper.getSession().onConnect(sessionWrapper.getSession().getAtmosphereResourceImpl(), sessionWrapper);
			sessionWrapper.initiated(true);
		} catch (Exception e) {
			e.printStackTrace();
			//outbound.disconnect();
			sessionWrapper.getSession().onShutdown();
		}
		
	}
	
	@Override
	public void onClose(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onClose event = " + event);
		
		sessionWrapper.getSession().onClose(event.message());
	}
	
}
