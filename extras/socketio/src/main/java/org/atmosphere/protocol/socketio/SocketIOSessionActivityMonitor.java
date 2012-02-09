/*
 * Copyright 2012 Sebastien Dionne
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.protocol.socketio;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
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
