package org.atmosphere.protocol.socketio;



public class SocketIOClosedException extends SocketIOException {
	private static final long serialVersionUID = 1L;

	public SocketIOClosedException() {
		super();
	}

	public SocketIOClosedException(String message) {
		super(message);
	}

	public SocketIOClosedException(String message, Throwable cause) {
		super(message, cause);
	}

	public SocketIOClosedException(Throwable cause) {
		super(cause);
	}
}
