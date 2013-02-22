package org.atmosphere.cache;

import static org.atmosphere.cpr.HeaderConfig.X_CACHE_DATE;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.atmosphere.cpr.AtmosphereResource;


public class HeaderBroadcasterCachePlus extends HeaderBroadcasterCache { 
	
	//assuming that only one instance of HeaderBroadcasterCachePlus exists. Otherwise the following static fields can't serve their purpose. 
	public static Hashtable<String, Long> messageCachedTimes = new Hashtable<String, Long>(); 
	public static Hashtable<String, Boolean> messageCachedTimesFlag = new Hashtable<String, Boolean>();
	public static final ReadWriteLock readWriteLockForMessageCachedTimes = new ReentrantReadWriteLock();
	
	
	@Override
	public void start() {
		scheduledFuture = reaper.scheduleAtFixedRate(new Runnable() {

            public void run() {
                readWriteLock.writeLock().lock();
                try {
                    long now = System.nanoTime();
                    List<CacheMessage> expiredMessages = new ArrayList<CacheMessage>();

                    for (CacheMessage message : messages) {
                        if (TimeUnit.NANOSECONDS.toMillis(now - message.getCreateTime()) > maxCacheTime) {
                            expiredMessages.add(message);
                        }
                    }

                    for (CacheMessage expiredMessage : expiredMessages) {
                        messages.remove(expiredMessage);
                        messagesIds.remove(expiredMessage.getId());
                        
                        readWriteLockForMessageCachedTimes.writeLock().lock();
                        try {
                        	Boolean flag = messageCachedTimesFlag.get(expiredMessage.getMessage().toString());
                        	if(flag!=null && !flag) {
                        		messageCachedTimesFlag.remove(expiredMessage.getMessage().toString());
                        		messageCachedTimes.remove(expiredMessage.getMessage().toString());
                        	}
                        } finally {
                        	readWriteLockForMessageCachedTimes.writeLock().unlock();
                        }
                        
                    }
                } finally {
                    readWriteLock.writeLock().unlock();
                }
            }
        }, 0, invalidateCacheInterval, TimeUnit.MILLISECONDS);
	}
	
	@Override
	public void addToCache(String broadcasterId, AtmosphereResource r, Message e) {
        long now = System.nanoTime();
        
        
        readWriteLockForMessageCachedTimes.writeLock().lock();
        try {
            if(messageCachedTimes.get(e.message.toString())==null) {
            	messageCachedTimes.put(e.message.toString(), now);
            } else {
            	//let timestamp be of old message that is same as current message. In this case there might be duplicate deliveries.
            }
            messageCachedTimesFlag.put(e.message.toString(), true);
        } finally {
        	readWriteLockForMessageCachedTimes.writeLock().unlock();
        }

        
        
        put(e, now);
        if (r != null) {
            r.getResponse().setHeader(X_CACHE_DATE, String.valueOf(now));
        }
	}	
	public CacheMessage getCacheMessage(String id) {
		return null;
	}

}
