
package org.atmosphere.protocol.socketio;

import java.util.ArrayList;
import java.util.List;

public class SocketIOFrame {
	public static final char SEPERATOR_CHAR = '~';
	public enum FrameType {
		UNKNOWN(-1),
		CLOSE(0),
		SESSION_ID(1),
		HEARTBEAT_INTERVAL(2),
		PING(3),
		PONG(4),
		DATA(0xE),
		FRAGMENT(0xF);

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
				return CLOSE;
			case 1:
				return SESSION_ID;
			case 2:
				return HEARTBEAT_INTERVAL;
			case 3:
				return PING;
			case 4:
				return PONG;
			case 0xE:
				return DATA;
			case 0xF:
				return FRAGMENT;
			default:
				return UNKNOWN;
			}
		}
	}

	public static final int TEXT_MESSAGE_TYPE = 0;
	public static final int JSON_MESSAGE_TYPE = 1;
	
	private static boolean isHexDigit(String str, int start, int end) {
		for (int i = start; i < end; i++) {
			char c = str.charAt(i);
			if (!Character.isDigit(c) &&
					c < 'A' && c > 'F' && c < 'a' && c > 'f') {
				return false;
			}
		}
		return true;
	}
	
	public static List<SocketIOFrame> parse(String data) {
		List<SocketIOFrame> messages = new ArrayList<SocketIOFrame>();
		int idx = 0;

		// Parse the data and silently ignore any part that fails to parse properly.
		while (data.length() > idx && data.charAt(idx) == SEPERATOR_CHAR) {
			int start = idx + 1;
			int end = data.indexOf(SEPERATOR_CHAR, start);

			if (-1 == end || start == end || !isHexDigit(data, start, end)) {
				break;
			}

			int mtype = 0;
			int ftype = Integer.parseInt(data.substring(start, start + 1), 16);

			FrameType frameType = FrameType.fromInt(ftype);
			if (frameType == FrameType.UNKNOWN) {
				break;
			}

			if (end - start > 1) {
				mtype = Integer.parseInt(data.substring(start + 1, end), 16);
			}
			
			start = end + 1;
			end = data.indexOf(SEPERATOR_CHAR, start);

			if (-1 == end || start == end || !isHexDigit(data, start, end)) {
				break;
			}
			
			int size = Integer.parseInt(data.substring(start, end), 16);

			start = end + 1;
			end = start + size;

			if (data.length() < end) {
				break;
			}
			
			messages.add(new SocketIOFrame(frameType, mtype, data.substring(start, end)));
			idx = end;
		}
		
		return messages;
	}
	
	public static String encode(FrameType type, int messageType, String data) {
		StringBuilder str = new StringBuilder(data.length() + 16);
		str.append(SEPERATOR_CHAR);
		str.append(Integer.toHexString(type.value()));
		if (messageType != TEXT_MESSAGE_TYPE) {
			str.append(Integer.toHexString(messageType));
		}
		str.append(SEPERATOR_CHAR);
		str.append(Integer.toHexString(data.length()));
		str.append(SEPERATOR_CHAR);
		str.append(data);
		return str.toString();
	}
	
	private final FrameType frameType;
	private final int messageType;
	private final String data;
	
	public SocketIOFrame(FrameType frameType, int messageType, String data) {
		this.frameType = frameType;
		this.messageType = messageType;
		this.data = data;
	}
	
	public FrameType getFrameType() {
		return frameType;
	}
	
	public int getMessageType() {
		return messageType;
	}
	
	public String getData() {
		return data;
	}
	
	public String encode() {
		return encode(frameType, messageType, data);
	}
}
