package org.atmosphere.protocol.socketio;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class SocketIOSessionActivityMonitor {

	protected ScheduledExecutorService executor = null;
	protected ScheduledFuture<Boolean> future = null;
	protected int delay = 0;
	protected TimeUnit timeUnit = null;
	
	public SocketIOSessionActivityMonitor(ScheduledExecutorService executor){
		this.executor = executor;
	}
	
	public int getDelay() {
		return delay;
	}

	public void setDelay(int delay) {
		this.delay = delay;
	}

	public TimeUnit getTimeUnit() {
		return timeUnit;
	}

	public void setTimeUnit(TimeUnit timeUnit) {
		this.timeUnit = timeUnit;
	}

	public abstract Callable<Boolean> getCommand();
	
	public void start(){
		future = executor.schedule(getCommand(), delay, timeUnit);
	}
	
	public void cancel(){
		future.cancel(true);
	}

}
