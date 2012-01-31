package org.atmosphere.protocol.socketio.transport;

public enum DisconnectReason {
	UNKNOWN(-1),
	CONNECT_FAILED(1),	// A connection attempt failed.
	DISCONNECT(2),		// Disconnect was called explicitly.
	TIMEOUT(3),			// A timeout occurred.
	CLOSE_FAILED(4),	// The connection dropped before an orderly close could complete.
	ERROR(5),			// A GET or POST returned an error, or an internal error occurred.
	CLOSED_REMOTELY(6),	// Remote end point initiated a close.
	CLOSED(6);			// Locally initiated close succeeded.

	private int value;
	private DisconnectReason(int v) { this.value = v; }
	public int value() { return value; }
	
	public static DisconnectReason fromInt(int val) {
		switch (val) {
		case 1:
			return CONNECT_FAILED;
		case 2:
			return DISCONNECT;
		case 3:
			return TIMEOUT;
		case 4:
			return CLOSE_FAILED;
		case 5:
			return ERROR;
		case 6:
			return CLOSED_REMOTELY;
		case 7:
			return CLOSED;
		default:
			return UNKNOWN;
		}
	}
}