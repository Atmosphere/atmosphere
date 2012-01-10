package org.atmosphere.protocol.socketio.cache;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Sebastien Dionne
 */
public class SocketIOBroadcasterCache implements BroadcasterCache<HttpServletRequest, HttpServletResponse, String> {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOBroadcasterCache.class);
	
	protected ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
	
	protected ConcurrentMap<AtmosphereResource<HttpServletRequest, HttpServletResponse>, Queue<String>> cache = new ConcurrentHashMap<AtmosphereResource<HttpServletRequest, HttpServletResponse>, Queue<String>>();
	
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
	public void addToCache(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource, String object) {
		
		logger.info("Message to cache : " + object);
		
		if(resource==null){
			logger.warn("Impossible to cache : " + object);
			return;
		}
		
		Queue<String> queue = null;
		
		if(!cache.containsKey(resource)){
			queue = new ConcurrentLinkedQueue<String>();
			cache.put(resource, queue);
		} else {
			queue = cache.get(resource);
		}
		
		queue.add(object);
		
	}

	@Override
	public List<String> retrieveFromCache(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource) {
		
		if(resource==null){
			logger.info("retrieveFromCache resource=NULL");
			return null;
		}
		
		logger.info("retrieveFromCache=" + resource.getRequest().getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID));
		
		
		if(cache.containsKey(resource)){
			List<String> list = new LinkedList<String>();
			
			Queue<String> queue = new ConcurrentLinkedQueue<String>();
			
			while(!queue.isEmpty()){
				list.add(queue.poll());
			}
			
			return list;
			
		}
		
		return null;
	}
}
