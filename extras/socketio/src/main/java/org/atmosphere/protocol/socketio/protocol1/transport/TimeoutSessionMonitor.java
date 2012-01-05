package org.atmosphere.protocol.socketio.protocol1.transport;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionActivityMonitor;
import org.atmosphere.protocol.socketio.SocketIOSessionManager;

public class TimeoutSessionMonitor extends SocketIOSessionActivityMonitor {

	private SocketIOSession session = null;
	
	public TimeoutSessionMonitor(SocketIOSession session, ScheduledExecutorService executor) {
		super(executor);
		this.session = session;
	}

	@Override
	public Callable<Boolean> getCommand() {
		return new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				session.timeout();
				return true;
			}
		};
	}

}
