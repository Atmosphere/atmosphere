package org.atmosphere.protocol.socketio;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;

public interface SocketIOAtmosphereHandler<F, G> extends AtmosphereHandler<F, G> {
	
	
	public static final String SocketIOSessionOutbound = "SocketIOSessionOutbound";
	
	public static final String SOCKETIO_SESSION_ID = SocketIOAtmosphereHandler.class.getPackage().getName() + ".sessionid";
	
	
	/**
     * Called when the connection is established. This will only ever be called once.
     *
     * @param outbound The SocketOutbound associated with the connection
     */
	void onConnect(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SocketIOSessionOutbound handler) throws IOException;
	void onDisconnect() throws IOException;
	
    /**
     * Called when the socket connection is closed. This will only ever be called once.
     * This method may be called instead of onConnect() if the connection handshake isn't
     * completed successfully.
     *
     * @param reason       The reason for the disconnect.
     * @param errorMessage Possibly non null error message associated with the reason for disconnect.
     */
    void onDisconnect(DisconnectReason reason, String errorMessage);
    
    /**
     * Called when the socket connection is closed. This will only ever be called once.
     * This method may be called instead of onConnect() if the connection handshake isn't
     * completed successfully.
     *
     * @param reason       The reason for the disconnect.
     * @param errorMessage Possibly non null error message associated with the reason for disconnect.
     */
    void onDisconnect(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SocketIOSessionOutbound handler, DisconnectReason reason);

    /**
     * Called one per arriving message.
     *
     * @param messageType
     * @param message
     */
    void onMessage(AtmosphereResource<HttpServletRequest, HttpServletResponse> event, SocketIOSessionOutbound handler, String message);
	
}
