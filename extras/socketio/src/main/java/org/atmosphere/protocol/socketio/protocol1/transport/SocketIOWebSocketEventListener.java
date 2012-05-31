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
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.util.List;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOWebSocketSessionWrapper;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl.PacketType;
import org.atmosphere.websocket.WebSocketEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOWebSocketEventListener implements WebSocketEventListener {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketEventListener.class);
	private SocketIOWebSocketSessionWrapper sessionWrapper = null;
	
	
	public void setSessionWrapper(SocketIOWebSocketSessionWrapper sessionWrapper){
		this.sessionWrapper = sessionWrapper;
	}
	
	public SocketIOWebSocketSessionWrapper getSessionWrapper(){
		return sessionWrapper;
	}
	
	@Override
	public void onThrowable(AtmosphereResourceEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onThrowable");
		
	}
	
	@Override
	public void onSuspend(AtmosphereResourceEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onSuspend");
		
	}
	
	@Override
	public void onResume(AtmosphereResourceEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onResume");
		
	}
	
	@Override
	public void onDisconnect(AtmosphereResourceEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onDisconnect");
		sessionWrapper.onDisconnect();
	}
	
	@Override
	public void onBroadcast(AtmosphereResourceEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onBroadcast");
		
	}
	
	@Override
	public void onMessage(WebSocketEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onMessage");
		
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
			List<SocketIOPacketImpl> messages = null;
			try {
				messages = SocketIOPacketImpl.parse(event.message());
			} catch (SocketIOException e) {
				e.printStackTrace();
			}
			
			if(messages!=null && !messages.isEmpty()){
				SocketIOSession session = sessionWrapper.getSession();
				for (SocketIOPacketImpl msg: messages) {
					session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg.getData());
				}
			}
			
		}
	}
	
	@Override
	public void onHandshake(WebSocketEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onHandshake event = " + event);
		
	}
	
	@Override
	public void onDisconnect(WebSocketEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onDisconnect event = " + event);
		
	}
	
	@Override
	public void onControl(WebSocketEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onControl event = " + event);
		
	}
	
	@Override
	public void onConnect(WebSocketEvent event) {
		logger.trace("calling from " + this.getClass().getName() + " : " + "onConnect");
		
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
		logger.trace("calling from " + this.getClass().getName() + " : " + "onClose event = " + event);
		
		sessionWrapper.getSession().onClose(event.message());
	}
	
}
