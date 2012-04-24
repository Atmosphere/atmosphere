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
package org.atmosphere.protocol.socketio.cache;

import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOBroadcasterCache implements BroadcasterCache {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOBroadcasterCache.class);
	
	protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
	
	protected ConcurrentMap<AtmosphereResource, Queue<Object>> cache = new ConcurrentHashMap<AtmosphereResource, Queue<Object>>();
	
    public SocketIOBroadcasterCache() {
    }

    @Override
	public void start() {
    	reaper.scheduleAtFixedRate(new Runnable() {

            public void run() {
            	logger.error("cleanup SocketIOBroadcasterCache");
            }
        }, 0, 60, TimeUnit.SECONDS);
		
	}

	@Override
	public void stop() {
		reaper.shutdown();
	}

	@Override
	public void addToCache(AtmosphereResource resource, Object object) {
		
		if(resource==null){
			logger.warn("Impossible to cache because resource is null: " + object);
			return;
		}
		
		logger.info("Message from : " + resource.getRequest().getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID) + " message to cache : " + object);
		
		Queue<Object> queue = null;
		
		if(!cache.containsKey(resource)){
			queue = new ConcurrentLinkedQueue<Object>();
			cache.put(resource, queue);
		} else {
			queue = cache.get(resource);
		}
		
		queue.add(object);
		
	}

	@Override
	public List<Object> retrieveFromCache(AtmosphereResource resource) {
		
		if(resource==null){
			logger.info("retrieveFromCache resource=NULL");
			return null;
		}
		
		logger.info("retrieveFromCache sessionid=" + resource.getRequest().getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID));
		if(resource.getRequest().getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID)==null){
			return null;
		}
		
		for (Entry<AtmosphereResource, Queue<Object>> entry : cache.entrySet()) {
			logger.info("SessionID Cached = " + entry.getKey().getRequest().getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID));
		}
		
		if(cache.containsKey(resource)){
			List<Object> list = new LinkedList<Object>();
			
			Queue<String> queue = new ConcurrentLinkedQueue<String>();
			
			while(!queue.isEmpty()){
				list.add(queue.poll());
			}
			
			return list;
			
		}
		
		return null;
	}
}
