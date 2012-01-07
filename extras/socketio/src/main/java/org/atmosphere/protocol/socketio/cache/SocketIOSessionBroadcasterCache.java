package org.atmosphere.protocol.socketio.cache;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.atmosphere.cache.BroadcasterCacheBase;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;

/**
 *
 * @author Sebastien Dionne
 */
public class SocketIOSessionBroadcasterCache extends BroadcasterCacheBase {

    public SocketIOSessionBroadcasterCache() {
    }

    /**
     * {@inheritDoc}
     */
    public void cache(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r, CachedMessage cm) {
        r.getRequest().getSession(true).setAttribute(BROADCASTER_CACHE_TRACKER, cm);
    }
    
    public synchronized List<Object> retrieveFromCache(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r) {
    	
    	List<Object> list = new ArrayList<Object>();
    	
        CachedMessage cm = retrieveLastMessage(r);

        if (cm != null) {
        	// est-ce que l'item est toujours dans la cache
        	if(!queue.isEmpty()){
	            if (!queue.contains(cm)) {
	                list.addAll(queue);
	            } else {
	            	int index = queue.indexOf(cm);
	            	if(index<queue.size()-1){
	            		list.addAll(queue.subList(index, queue.size()));
	            	}
	            }
	            
	            // on reset la cache au dernier item
	            cache(r, queue.get(queue.size()-1));
        	}
        } 
        
        return list;

    }
    

    /**
     * {@inheritDoc}
     */
    public CachedMessage retrieveLastMessage(final AtmosphereResource<HttpServletRequest, HttpServletResponse> r) {

        HttpSession session = r.getRequest().getSession(false);
        if (session == null) {
            session = r.getRequest().getSession(true);
        }

        return (CachedMessage) session.getAttribute(BROADCASTER_CACHE_TRACKER);
    }
}
