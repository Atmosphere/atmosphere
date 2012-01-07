package org.atmosphere.protocol.socketio.protocol1.transport;

import java.util.ArrayList;
import java.util.List;

import org.atmosphere.protocol.socketio.SocketIOPacket;

public class SocketIOPacketImpl implements SocketIOPacket {
	
	private final PacketType packetType;
	
	private final String id;
	private final String endpoint;
	private final String data;
	private boolean padding = false;
	
	public SocketIOPacketImpl(PacketType frameType){
		this(frameType, null, null, null, false);
	}
	
	public SocketIOPacketImpl(PacketType frameType, String data){
		this(frameType, null, null, data, false);
	}
	
	public SocketIOPacketImpl(PacketType frameType, String data, boolean padding){
		this(frameType, null, null, data, false);
	}
	
	public SocketIOPacketImpl(PacketType frameType, String id, String endpoint, String data){
		this(frameType, id, endpoint, data, false);
	}
	
	public SocketIOPacketImpl(PacketType frameType, String id, String endpoint, String data, boolean padding){
		this.packetType = frameType;
		this.id = id;
		this.endpoint = endpoint;
		this.data = data;
		this.padding = padding;
	}
	
	public PacketType getFrameType() {
		return packetType;
	}
	
	public String getData() {
		return data;
	}
	
	public void setPadding(boolean padding) {
		this.padding = padding;
	}

	public String toString(){
		StringBuilder sb = new StringBuilder();
		
		if(padding && data!=null){
			sb.append('\ufffd').append(data.length()).append('\ufffd');
		}
		
		//[message type] ':' [message id ('+')] ':' [message endpoint] (':' [message data]) 
		sb.append(packetType.value).append(":");
		
		if(id!=null){
			sb.append(id);
		}
		
		sb.append(":");
		
		if(endpoint!=null){
			sb.append(endpoint);
		}
		
		if(data!=null){
			sb.append(":");
			sb.append(data);
		}
		
		return sb.toString();
	}
	
	
	public enum PacketType {
		UNKNOWN(-1),
		DISCONNECT(0),
		CONNECT(1),
		HEARTBEAT(2),
		MESSAGE(3),
		JSON(4),
		EVENT(5),
		ACK(6),
		ERROR(7),
		NOOP(8);

		private int value;
		
		PacketType(int value) {
			this.value = value;
		}
		
		public int value() {
			return value;
		}
		
		public static PacketType fromInt(int val) {
			switch (val) {
			case 0:
				return DISCONNECT;
			case 1:
				return CONNECT;
			case 2:
				return HEARTBEAT;
			case 3:
				return MESSAGE;
			case 4:
				return JSON;
			case 5:
				return EVENT;
			case 6:
				return ACK;
			case 7:
				return ERROR;
			case 8:
				return NOOP;
			default:
				return UNKNOWN;
			}
		}
	}

	public static List<SocketIOPacketImpl> parse(String data) {
		List<SocketIOPacketImpl> messages = new ArrayList<SocketIOPacketImpl>();

		if(data==null || data.length()==0){
			return messages;
		}
		
		String array[] = data.split(":");
		
		if(array.length<1){
			System.err.println("Message invalide=" + data);
			return messages;
		}
		
		String type = null;
		String id = null;
		String endpoint= null;
		String message = null;
		
		//[message type] ':' [message id ('+')] ':' [message endpoint] (':' [message data]) 
		
		// 0::/test
		// 1::/test?my=param
		// 2::
		// 3:1::blabla
		// 4:1::{"a":"b"}
		// 5:::{"args":["user1","user2 ecrit coucou"],"name":"user message"}
		// 6:::4+["A","B"]  ou 6:::4
		// '7::' [endpoint] ':' [reason] '+' [advice]
		// 8 .. pas d'exemple
		
		if(array.length==1){
			type = array[0];
		} else if(array.length==2){
			type = array[0];
			id = array[1];
		} else if(array.length==3){
			type = array[0];
			id = array[1];
			endpoint= array[2];
		} else {
			
			type = array[0];
			id = array[1];
			endpoint= array[2];
			
			// maintenant, il faut extraire le message s'il y en a un
			
			// on saute les 3 premiers ":" pour chercher s'il y a un message
			
			int start = data.indexOf(":");
			
			start = data.indexOf(":", ++start);
			start = data.indexOf(":", ++start);
			
			if(start>-1){
				message = data.substring(start+1);
			}
		}
		
		messages.add(new SocketIOPacketImpl(PacketType.fromInt(Integer.parseInt(type)), id, endpoint, message));
		
		return messages;
	}
	
	public static void main(String[] args) throws Exception {
		List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse("[5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}]");
		
		for (SocketIOPacketImpl msg: messages) {
			switch(msg.getFrameType()){
				case MESSAGE:
				case JSON:
				case EVENT:
				case ACK:
				case ERROR:
					System.out.println(msg.data);
					break;
				default:
					System.err.println("DEVRAIT PAS ARRIVER onStateChange SocketIOEvent msg = " + msg );
			}
		}
	}
	
}
