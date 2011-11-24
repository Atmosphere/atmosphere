package org.atmosphere.protocol.socketio;

public interface SocketIOProtocol {
	
	// apres la connection, state==1
	void onConnect();
	// state==4
	void onDisconnect();
	void onOpen();
	void onClose();
	void onError();
	void onRetry();
	void onReconnect();
	//state==3
	void onMessage();
}
