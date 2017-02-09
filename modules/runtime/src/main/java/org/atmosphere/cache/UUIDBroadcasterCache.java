/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.cache;

import static org.atmosphere.runtime.ApplicationConfig.UUIDBROADCASTERCACHE_CLIENT_IDLETIME;
import static org.atmosphere.runtime.ApplicationConfig.UUIDBROADCASTERCACHE_IDLE_CACHE_INTERVAL;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.BroadcasterCache;
import org.atmosphere.runtime.BroadcasterCacheListener;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.UUIDProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An improved {@link BroadcasterCache} implementation that is based on the unique identifier (UUID) that all
 * {@link AtmosphereResource}s have.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public class UUIDBroadcasterCache implements BroadcasterCache {

    private final static Logger logger = LoggerFactory.getLogger(UUIDBroadcasterCache.class);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<CacheMessage>> messages = new ConcurrentHashMap<String, ConcurrentLinkedQueue<CacheMessage>>();
    private final Map<String, Long> activeClients = new ConcurrentHashMap<String, Long>();
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();
    private ScheduledFuture scheduledFuture;
    protected ScheduledExecutorService taskScheduler;
    private long clientIdleTime = TimeUnit.SECONDS.toMillis(60); // 1 minutes
    private long invalidateCacheInterval = TimeUnit.SECONDS.toMillis(30); // 30 seconds
    private boolean shared = true;
    protected final List<BroadcasterCacheListener> listeners = new LinkedList<BroadcasterCacheListener>();
    private UUIDProvider uuidProvider;

    @Override
    public void configure(AtmosphereConfig config) {
        Object o = config.properties().get("shared");
        if (o != null) {
            shared = Boolean.parseBoolean(o.toString());
        }

        if (shared) {
            taskScheduler = ExecutorsFactory.getScheduler(config);
        } else {
            taskScheduler = Executors.newSingleThreadScheduledExecutor();
        }

        clientIdleTime = TimeUnit.SECONDS.toMillis(
                Long.valueOf(config.getInitParameter(UUIDBROADCASTERCACHE_CLIENT_IDLETIME, "60")));

        invalidateCacheInterval = TimeUnit.SECONDS.toMillis(
                Long.valueOf(config.getInitParameter(UUIDBROADCASTERCACHE_IDLE_CACHE_INTERVAL, "30")));

        uuidProvider = config.uuidProvider();
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                invalidateExpiredEntries();
            }
        }, 0, invalidateCacheInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        cleanup();

        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public void cleanup() {
        messages.clear();
        activeClients.clear();
        inspectors.clear();

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, String uuid, BroadcastMessage message) {
        if (logger.isTraceEnabled()) {
            logger.trace("Adding for AtmosphereResource {} cached messages {}", uuid, message.message());
            logger.trace("Active clients {}", activeClients());
        }

        String messageId = uuidProvider.generateUuid();
        boolean cache = true;
        if (!inspect(message)) {
            cache = false;
        }

        CacheMessage cacheMessage = new CacheMessage(messageId, message.message(), uuid, broadcasterId);
        if (cache) {
            if (uuid.equals(NULL)) {
                //no clients are connected right now, caching message for all active clients
                for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
                    addMessage(broadcasterId, entry.getKey(), cacheMessage);
                }
            } else {
                cacheCandidate(broadcasterId, uuid);
                addMessage(broadcasterId, uuid, cacheMessage);
            }
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, String uuid) {
    
        cacheCandidate(broadcasterId, uuid);
        ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(uuid);
        CacheMessage message;

        List<Object> result = new ArrayList<>();

        if (clientQueue == null) {
              logger.trace("client queue is null (not yet created), hence returning back for broadcaster {} and uuid {}",
                              broadcasterId, uuid);
              return result;
         }

        while ((message = clientQueue.poll()) != null) {
             result.add(message);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Retrieved for AtmosphereResource {} cached messages {}", uuid, result);
            logger.trace("Available cached message {}", messages);
        }

        return result;
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage message) {
        ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(uuid);
    
        if (clientQueue != null) {
            logger.trace("Removing for broadcaster {} AtmosphereResource {} cached message {}", broadcasterId, uuid, message.getMessage());
            notifyRemoveCache(broadcasterId, message);
            clientQueue.remove(message);
        }
        return this;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    @Override
    public BroadcasterCache addBroadcasterCacheListener(BroadcasterCacheListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public BroadcasterCache removeBroadcasterCacheListener(BroadcasterCacheListener l) {
        listeners.remove(l);
        return this;
    }

    protected String uuid(AtmosphereResource r) {
        return r.uuid();
    }

    private void addMessage(String broadcasterId, String clientId, CacheMessage message) {

        ConcurrentLinkedQueue<CacheMessage> clientQueue = messages.get(clientId);

        if (clientQueue == null) {
             if (activeClients.get(clientId) != null) {

                clientQueue = new ConcurrentLinkedQueue<>();
                if (messages.putIfAbsent(clientId, clientQueue) == null) {
                      logger.debug("new queue created for message {}, broadcaster {} for resource {} ",
                              message.getMessage(), broadcasterId, clientId);
                 }
             } else {
                     logger.debug("Client {} is no longer active. Not caching message {} for broadcaster {}", clientId, message, broadcasterId);
                     return;
             }
        }

        notifyAddCache(broadcasterId, message);

        if (logger.isDebugEnabled()) {
            logger.debug("offering message {}, broadcaster {} for resource {}", message.getMessage(), broadcasterId, clientId);
        }
        
        messages.get(clientId).offer(message);
        
    }

    private void notifyAddCache(String broadcasterId, CacheMessage message) {
        for (BroadcasterCacheListener l : listeners) {
            try {
                l.onAddCache(broadcasterId, message);
            } catch (Exception ex) {
                logger.warn("Listener exception", ex);
            }
        }
    }

    private void notifyRemoveCache(String broadcasterId, CacheMessage message) {
        for (BroadcasterCacheListener l : listeners) {
            try {
                l.onRemoveCache(broadcasterId, message);
            } catch (Exception ex) {
                logger.warn("Listener exception", ex);
            }
        }
    }

    public Map<String, ConcurrentLinkedQueue<CacheMessage>> messages() {
        return messages;
    }

    public Map<String, Long> activeClients() {
        return activeClients;
    }

    protected boolean inspect(BroadcastMessage m) {
        for (BroadcasterCacheInspector b : inspectors) {
            if (!b.inspect(m))
                return false;
        }
        return true;
    }

    public void setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
        scheduledFuture.cancel(true);
        start();
    }

    public void setClientIdleTime(long clientIdleTime) {
        this.clientIdleTime = clientIdleTime;
    }

    protected void invalidateExpiredEntries() {

        long now = System.currentTimeMillis();

        Set<String> inactiveClients = new HashSet<>();
        for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > clientIdleTime) {
                logger.trace("Invalidate client {}", entry.getKey());
                inactiveClients.add(entry.getKey());
            }
        }

        for (String clientId : inactiveClients) {
            activeClients.remove(clientId);
            ConcurrentLinkedQueue<CacheMessage> queue = messages.remove(clientId);
            if (queue != null) {
                logger.debug("removed  client queue of size: {} for client uuid {}", queue.size(), clientId);
                notifyRemoveCache(queue);
            } else {
                logger.trace("queue is already null for client: {}", clientId);
            }

        }

        for (String clientId : messages().keySet()) {
            if (!activeClients().containsKey(clientId)) {
                messages().get(clientId).clear();
            }
        }
    }

    private void notifyRemoveCache(ConcurrentLinkedQueue<CacheMessage> messageQueue) {
        for(CacheMessage message: messageQueue){
           notifyRemoveCache(message.getBroadcasterId(), message);
        }
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        activeClients.remove(r.uuid());
        return this;
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        long now = System.currentTimeMillis();
        activeClients.put(uuid, now);
        return this;
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }

    public List<BroadcasterCacheListener> listeners() {
        return listeners;
    }

    public List<BroadcasterCacheInspector> inspectors() {
        return inspectors;
    }
}
