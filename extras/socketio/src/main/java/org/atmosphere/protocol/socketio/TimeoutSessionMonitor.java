package org.atmosphere.protocol.socketio;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;


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
