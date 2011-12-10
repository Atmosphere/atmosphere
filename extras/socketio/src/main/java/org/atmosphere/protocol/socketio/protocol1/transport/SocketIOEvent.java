
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.util.ArrayList;
import java.util.List;

public class SocketIOEvent {
	
	private final FrameType frameType;
	
	private final String id;
	private final String endpoint;
	private final String data;
	
	public SocketIOEvent(FrameType frameType, String id, String endpoint, String data){
		this.frameType = frameType;
		this.id = id;
		this.endpoint = endpoint;
		this.data = data;
	}
	
	public FrameType getFrameType() {
		return frameType;
	}
	
	public String getData() {
		return data;
	}
	
	
	public enum FrameType {
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
		
		FrameType(int value) {
			this.value = value;
		}
		
		public int value() {
			return value;
		}
		
		public static FrameType fromInt(int val) {
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

	public static List<SocketIOEvent> parse(String data) {
		List<SocketIOEvent> messages = new ArrayList<SocketIOEvent>();

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
		
		messages.add(new SocketIOEvent(FrameType.fromInt(Integer.parseInt(type)), id, endpoint, message));
		
		return messages;
	}
	
	public static void main(String[] args) throws Exception {
		List<SocketIOEvent> messages = SocketIOEvent.parse("[5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[\"test connected\"],\"name\":\"announcement\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}, 5:::{\"args\":[],\"name\":\"disconnect\"}]");
		
		for (SocketIOEvent msg: messages) {
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
