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
package org.atmosphere.samples.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.atmosphere.protocol.socketio.SocketIOSessionOutbound;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl.PacketType;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple SocketIOAtmosphereHandler that implements the logic to build a
 * SocketIO Chat application.
 * 
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class ChatAtmosphereHandler implements SocketIOAtmosphereHandler {

	private static final Logger logger = LoggerFactory.getLogger(ChatAtmosphereHandler.class);

	private static final ConcurrentMap<String, String> loggedUserMap = new ConcurrentSkipListMap<String, String>();

	/**
	 * When the {@link AtmosphereServlet} detect an {@link HttpServletRequest}
	 * maps to this {@link AtmosphereHandler}, the
	 * {@link AtmosphereHandler#onRequest} gets invoked and the response will be
	 * suspended depending on the http method, e.g. GET will suspend the
	 * connection, POST will broadcast chat message to suspended connection.
	 * 
	 * @param event
	 *            An {@link AtmosphereResource}
	 * @throws java.io.IOException
	 */
	@SuppressWarnings("unused")
	public void onRequest(AtmosphereResource event) throws IOException {
		logger.debug("onRequest");
	}

	/**
	 * Invoked when a call to {@link Broadcaster#broadcast(java.lang.Object)} is
	 * issued or when the response times out, e.g whne the value
	 * {@link AtmosphereResource#suspend(long)} expires.
	 * 
	 * @param event
	 *            An {@link AtmosphereResourceEvent}
	 * @throws java.io.IOException
	 */
	@SuppressWarnings("unused")
	public void onStateChange(AtmosphereResourceEvent event) throws IOException {
		// logger.error("onStateChange event = " + event);

		if (event.isResuming() || event.isResumedOnTimeout()) {
			return;
		}

		HttpServletRequest request = event.getResource().getRequest();
		HttpServletResponse response = event.getResource().getResponse();

		logger.error("onStateChange on SessionID=" + request.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID) + "  Method=" + request.getMethod());

		SocketIOSessionOutbound outbound = (org.atmosphere.protocol.socketio.SocketIOSessionOutbound) request.getAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound);

		if (outbound != null && event.getMessage() != null) {
			try {

				if (event.getMessage().getClass().isArray()) {
					List<Object> list = Arrays.asList(event.getMessage());

					for (Object object : list) {
						List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(object.toString());
						outbound.sendMessage(messages);
					}

				} else if (event.getMessage() instanceof List) {
					@SuppressWarnings("unchecked")
					List<Object> list = List.class.cast(event.getMessage());

					for (Object object : list) {
						List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(object.toString());
						outbound.sendMessage(messages);
					}
				} else if (event.getMessage() instanceof String) {
					
					logger.info("onStateChange Sending message on resume : message = " + event.getMessage().toString());
					
					List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(event.getMessage().toString());
					outbound.sendMessage(messages);
				}

			} catch (Exception e) {
				e.printStackTrace();
				outbound.disconnect();
			}
		}

	}

	public void destroy() {
		logger.debug("destroy");
	}

	@SuppressWarnings("unused")
	public void onConnect(AtmosphereResource event, SocketIOSessionOutbound outbound) throws IOException {
		logger.debug("onConnect");
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

	@SuppressWarnings("unused")
	public void onMessage(AtmosphereResource event, SocketIOSessionOutbound outbound, String message) {

		if (outbound == null || message == null || message.length() == 0) {
			return;
		}

		HttpServletRequest request = event.getRequest();
		HttpServletResponse response = event.getResponse();
		
		try {
			logger.error("onMessage on SessionID=" + outbound.getSessionId() + "  : Message Received = " + message);

			
			if(outbound.getSessionId()==null){
				System.out.println("SessionID=null");
			}
			
			ObjectMapper mapper = new ObjectMapper();

			ChatJSONObject chat = mapper.readValue(message, ChatJSONObject.class);

			if (ChatJSONObject.LOGIN.equalsIgnoreCase(chat.name)) {

				request.getSession().setAttribute("LOGINNAME", chat.getArgs().toArray()[0]);
				
				String username = (String) chat.getArgs().toArray()[0];
				// est-il deja loggé ?
				if (loggedUserMap.containsValue(username)) {
					outbound.sendMessage(new SocketIOPacketImpl(PacketType.ACK, "1+[true]").toString());
				} else {
					//loggedUserMap.put((String) chat.getArgs().toArray()[0], (String) chat.getArgs().toArray()[0]);
					loggedUserMap.put(outbound.getSessionId(), username);

					try {
 
						ChatJSONObject out = new ChatJSONObject();

						out.setName(ChatJSONObject.USERCONNECTEDLIST);
						List list = new ArrayList();

						list.add(loggedUserMap);

						out.setArgs(list);
						
						List<SocketIOPacketImpl> loginMessagesList = new ArrayList(2);
						
						// envoie la confirmation du login
						loginMessagesList.add(new SocketIOPacketImpl(PacketType.ACK, "1+[false]"));
						
						// on envoye au user qui vient de se logger, la liste
						// des usernames dans le chat
						loginMessagesList.add(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out)));
						
						// on envoye les messages dans une liste pour etre sur d'avoir un separateur entre les messages pour eviter le cas suivant : 
						// 6:::1+[false]5:::{"name":"nicknames","args":[{"ff1":"ff1"}]}
						
						// envoye les messages seulement a ce client
						outbound.sendMessage(loginMessagesList);
						
						// DEBUG
						logger.debug("Broadcasting message = " + new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out), false).toString());

						// on broadcast la liste des usernames dans le chat aux
						// autres usagers
						event.getBroadcaster().broadcast(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out), false).toString(), event);

						// DEBUG
						logger.debug("Broadcasting message = " + new SocketIOPacketImpl(PacketType.EVENT, "{\"args\":[\"" + chat.getArgs().toArray()[0] + " connected\"],\"name\":\"announcement\"}", false).toString());

						// on broadcast le username du nouveau dans le chat aux
						// autres usagers
						event.getBroadcaster().broadcast(new SocketIOPacketImpl(PacketType.EVENT, "{\"args\":[\"" + chat.getArgs().toArray()[0] + " connected\"],\"name\":\"announcement\"}", false).toString(), event);

					} catch (Exception e) {
						e.printStackTrace();
						outbound.disconnect();
					}

				}

			} else if (ChatJSONObject.MESSAGE.equalsIgnoreCase(chat.name)) {

				// debug pour java.lang.IllegalStateException: No SessionManager
				String username = loggedUserMap.get(outbound.getSessionId());

				List<String> msg = new ArrayList<String>();
				msg.add(username);
				msg.addAll(chat.args);

				ChatJSONObject out = new ChatJSONObject();

				out.setName(ChatJSONObject.MESSAGE);
				out.setArgs(msg);

				// DEBUG
				logger.error("Broadcasting message = " + new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out)).toString());

				// on broadcast le nouveau message aux autres usagers
				event.getBroadcaster().broadcast(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out)).toString(), event);

			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public void onDisconnect(DisconnectReason reason, String message) {
		logger.error("onDisconnect DisconnectReason=" + reason + " message = " + message);

	}

	public void onDisconnect(AtmosphereResource event, SocketIOSessionOutbound outbound, DisconnectReason reason) {
		logger.error("onDisconnect from sessionid = " + outbound.getSessionId() + " username=" + loggedUserMap.get(outbound.getSessionId()));
		
		String sessionid = outbound.getSessionId();
		
		String username = loggedUserMap.get(sessionid);

		// on broadcast l'info aux autres usagers
		event.getBroadcaster().broadcast(new SocketIOPacketImpl(PacketType.EVENT, "{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}").toString(), event);
		
		// on enleve le username de la liste des personnes dans le chat
		loggedUserMap.remove(sessionid);
		
		// et on broadcast la liste des usagers connectes
		ObjectMapper mapper = new ObjectMapper();

		ChatJSONObject out = new ChatJSONObject();
		out.setName(ChatJSONObject.USERCONNECTEDLIST);
		List list = new ArrayList();
		list.add(loggedUserMap);
		out.setArgs(list);
		
		try {
			event.getBroadcaster().broadcast(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out), false).toString(), event);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
