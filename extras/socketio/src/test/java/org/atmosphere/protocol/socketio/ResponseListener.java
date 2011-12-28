package org.atmosphere.protocol.socketio;

public abstract class ResponseListener {
	
	public void onClose(){
		
	}
	
	public abstract void notify(String message);
}
