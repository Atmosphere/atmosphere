package org.atmosphere.protocol.socketio;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class SocketIOSessionActivityMonitor {

	protected ScheduledExecutorService executor = null;
	protected ScheduledFuture<Boolean> future = null;
	protected long delay = 0;
	protected TimeUnit timeUnit = TimeUnit.MILLISECONDS;
	
	public SocketIOSessionActivityMonitor(ScheduledExecutorService executor){
		this.executor = executor;
	}
	
	public long getDelay() {
		return delay;
	}

	public void setDelay(long delay) {
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
		if(future!=null){
			future.cancel(true);
		}
	}

}
