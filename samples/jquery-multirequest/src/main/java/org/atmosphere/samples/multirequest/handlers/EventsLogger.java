package org.atmosphere.samples.multirequest.handlers;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.websocket.WebSocketEventListener;

public class EventsLogger implements WebSocketEventListener {

	private static final Logger logger = Logger.getLogger(EventsLogger.class);

	public EventsLogger() {
	}

	public void onSuspend(
			final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.debug("onSuspend(): "
				+ event.getResource().getRequest().getRemoteAddr() + ":"
				+ event.getResource().getRequest().getRemotePort());
	}

	public void onResume(
			AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.debug("onResume(): "
				+ event.getResource().getRequest().getRemoteAddr() + ":"
				+ event.getResource().getRequest().getRemotePort());
	}

	public void onDisconnect(
			AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.debug("onDisconnect(): "
				+ event.getResource().getRequest().getRemoteAddr() + ":"
				+ event.getResource().getRequest().getRemotePort());
	}

	public void onBroadcast(
			AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.debug("onBroadcast(): " + event.getMessage());
	}

	public void onThrowable(
			AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
		logger.warn("onThrowable(): " + event);
	}

	public void onHandshake(WebSocketEvent event) {
		logger.debug("onHandshake(): " + event);
	}

	public void onMessage(WebSocketEvent event) {
		logger.debug("onMessage(): " + event);
	}

	public void onClose(WebSocketEvent event) {
		logger.debug("onClose(): " + event);
	}

	public void onControl(WebSocketEvent event) {
		logger.debug("onControl(): " + event);
	}

	public void onDisconnect(WebSocketEvent event) {
		logger.debug("onDisconnect(): " + event);
	}

	public void onConnect(WebSocketEvent event) {
		logger.debug("onConnect(): " + event);
	}
}