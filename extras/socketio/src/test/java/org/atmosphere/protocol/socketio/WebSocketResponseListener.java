package org.atmosphere.protocol.socketio;

public class WebSocketResponseListener extends ResponseListener {
	
	protected WebSocketWrapper wrapper = null;
	
	public WebSocketResponseListener(WebSocketWrapper wrapper){
		this.wrapper = wrapper;
	}
	
	ResponseListener listener = null;

	public ResponseListener getListener() {
		return listener;
	}

	public void setListener(ResponseListener listener) {
		this.listener = listener;
	}
	
	public void onClose(){
		listener.onClose();
	}
	
	public void notify(String message){
		listener.notify(message);
	}
	
}
