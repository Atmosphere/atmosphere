package org.atmosphere.protocol.socketio;

import java.io.IOException;

public class SocketIOException extends IOException {
	private static final long serialVersionUID = 1L;

	public SocketIOException() {
		super();
	}

	public SocketIOException(String message) {
		super(message);
	}

	public SocketIOException(Throwable cause) {
		super(cause);
	}

	public SocketIOException(String message, Throwable cause) {
		super(message, cause);
	}
}
