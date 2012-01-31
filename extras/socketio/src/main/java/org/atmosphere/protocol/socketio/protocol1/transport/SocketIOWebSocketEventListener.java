package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOWebSocketSessionWrapper;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl.PacketType;
import org.atmosphere.websocket.WebSocketEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketIOWebSocketEventListener implements WebSocketEventListener {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketEventListener.class);
	private SocketIOWebSocketSessionWrapper sessionWrapper = null;
	
	
	public SocketIOWebSocketEventListener(){
		logger.error("SocketIOWebSocketEventListener CONSTRUCTEUR");
	}
	
	public void setSessionWrapper(SocketIOWebSocketSessionWrapper sessionWrapper){
		this.sessionWrapper = sessionWrapper;
	}
	
	public SocketIOWebSocketSessionWrapper getSessionWrapper(){
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
		
		if (!sessionWrapper.isInitiated()) {
			if ("OPEN".equals(event.message())) {
				try {
					sessionWrapper.getSession().onConnect(sessionWrapper.getSession().getAtmosphereResourceImpl(), sessionWrapper);
					sessionWrapper.initiated(true);
				} catch (Exception e) {
					e.printStackTrace();
					try {
						sessionWrapper.webSocket().close();
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					sessionWrapper.getSession().onShutdown();
				}
			} else {
				try {
					sessionWrapper.webSocket().close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				sessionWrapper.getSession().onShutdown();
			}
		} else {
			List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(event.message());
			
			SocketIOSession session = sessionWrapper.getSession();
			for (SocketIOPacketImpl msg: messages) {
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
			event.webSocket().write(new SocketIOPacketImpl(PacketType.CONNECT).toString());
		} catch (IOException e) {
			e.printStackTrace();
			sessionWrapper.getSession().onShutdown();
		}
		
		try {
			sessionWrapper.getSession().setAtmosphereResourceImpl((AtmosphereResourceImpl) event.webSocket().resource());
			sessionWrapper.getSession().onConnect(sessionWrapper.getSession().getAtmosphereResourceImpl(), sessionWrapper);
			sessionWrapper.initiated(true);
		} catch (Exception e) {
			e.printStackTrace();
			sessionWrapper.getSession().onShutdown();
		}
		
	}
	
	@Override
	public void onClose(WebSocketEvent event) {
		logger.error("calling from " + this.getClass().getName() + " : " + "onClose event = " + event);
		
		sessionWrapper.getSession().onClose(event.message());
	}
	
}
