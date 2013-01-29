/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An improved {@link BroadcasterCache} implementation.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public class EventCacheBroadcasterCache implements BroadcasterCache {

    private final static Logger logger = LoggerFactory.getLogger(EventCacheBroadcasterCache.class);

    private final Map<String, ClientQueue> messages = new HashMap<String, ClientQueue>();

    private final Map<String, Long> activeClients = new HashMap<String, Long>();
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();
    private ScheduledFuture scheduledFuture;
    protected ScheduledExecutorService taskScheduler = Executors.newSingleThreadScheduledExecutor();

    private long clientIdleTime = TimeUnit.MINUTES.toMillis(2);//2 minutes

    private long invalidateCacheInterval = TimeUnit.MINUTES.toMillis(1);//1 minute

    public static class ClientQueue {

        private LinkedList<CacheMessage> queue = new LinkedList<CacheMessage>();

        private Set<String> ids = new HashSet<String>();

        public LinkedList<CacheMessage> getQueue() {
            return queue;
        }

        public Set<String> getIds() {
            return ids;
        }
    }

    public static class CacheMessage {

        private final Object message;

        private final String id;

        private CacheMessage(String id, Object message) {
            this.id = id;
            this.message = message;
        }

        public Object getMessage() {
            return message;
        }

        public String getId() {
            return id;
        }

        public String toString(){
            return message.toString();
        }
    }

    public void setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
    }

    public void setClientIdleTime(long clientIdleTime) {
        this.clientIdleTime = clientIdleTime;
    }

    public void setExecutorService(ScheduledExecutorService reaper) {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }

        if (taskScheduler != null) {
            stop();
        }
        this.taskScheduler = reaper;
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                invalidateExpiredEntries();
            }
        }, 0, invalidateCacheInterval, TimeUnit.MINUTES);
    }

    protected void invalidateExpiredEntries() {
        long now = System.nanoTime();
        synchronized (messages) {

            Set<String> inactiveClients = new HashSet<String>();

            for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
                if (now - entry.getValue() > clientIdleTime) {
                    inactiveClients.add(entry.getKey());
                }
            }

            for (String clientId : inactiveClients) {
                activeClients.remove(clientId);
                messages.remove(clientId);
            }

        }
    }

    @Override
    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        taskScheduler.shutdown();
    }

    @Override
    public void addToCache(String broadcasterId, AtmosphereResource r, Message e) {
        if (!inspect(e)) return;
        addCacheCandidate(broadcasterId, r, e.message);
    }

    /**
     * For backward compatibility with 1.0.9 and lower, use the method above.
     * @param broadcasterId
     * @param r
     * @param e
     * @return
     */
    public CacheMessage addCacheCandidate(String broadcasterId, AtmosphereResource r, Object e) {
        if (logger.isDebugEnabled()) {
            logger.debug("Adding for AtmosphereResource {} cached messages {}", r != null ? r.uuid() : "null", e);
            logger.debug("Active clients {}", activeClients());
        }

        long now = System.nanoTime();
        String messageId = UUID.randomUUID().toString();
        CacheMessage cacheMessage = new CacheMessage(messageId, e);
        synchronized (messages) {
            if (r == null) {
                //no clients are connected right now, caching message for all active clients
                for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
                    addMessageIfNotExists(entry.getKey(), cacheMessage);
                }
            } else {
                String clientId = uuid(r);

                activeClients.put(clientId, now);
                if (isAtmosphereResourceValid(r)) {//todo better to have cacheLost flag
                    /**
                     * This line is called for each AtmosphereResource (note that
                     * broadcaster.getAtmosphereResources() may not return the current AtmosphereResource because
                     * the resource may be destroyed by DefaultBroadcaster.executeAsyncWrite or JerseyBroadcasterUtil
                     * concurrently, that is why we need to check duplicates),
                     *
                     * Cache the message only once for the clients
                     * which are not currently connected to the server
                     */
                    Broadcaster broadcaster = getBroadCaster(r.getAtmosphereConfig(), broadcasterId);
                    List<AtmosphereResource> resources = new ArrayList<AtmosphereResource>(broadcaster.getAtmosphereResources());
                    Set<String> disconnectedClients = getDisconnectedClients(resources);
                    for (String disconnectedId : disconnectedClients) {
                        addMessageIfNotExists(disconnectedId, cacheMessage);
                    }
                } else {
                    /**
                     * Cache lost message, caching only for specific client.
                     * Preventing duplicate inserts because this method can be called
                     * concurrently from DefaultBroadcaster.executeAsyncWrite or JerseyBroadcasterUtil
                     * when calling cacheLostMessage
                     */
                    addMessageIfNotExists(clientId, cacheMessage);
                }
            }
        }
        return cacheMessage;
    }

    private String uuid(AtmosphereResource r) {
        return r.transport() == AtmosphereResource.TRANSPORT.WEBSOCKET
                                ? (String) r.getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID) : r.uuid();
    }

    private boolean isAtmosphereResourceValid(AtmosphereResource r) {
        return !r.isResumed()
                && !r.isCancelled()
                && AtmosphereResourceImpl.class.cast(r).isInScope();
    }

    private Set<String> getDisconnectedClients(List<AtmosphereResource> resources) {
        Set<String> ids = new HashSet<String>(activeClients.keySet());
        for (AtmosphereResource resource : resources) {
            ids.remove(resource.uuid());
        }
        return ids;
    }

    private Broadcaster getBroadCaster(AtmosphereConfig config, String broadcasterId) {
        BroadcasterFactory factory = config.getBroadcasterFactory();
        Broadcaster broadcaster = factory.lookup(broadcasterId, false);
        return broadcaster;
    }

    private void addMessageIfNotExists(String clientId, CacheMessage message) {
        if (!hasMessage(clientId, message.getId())) {
            addMessage(clientId, message);
        } else {
            logger.debug("Duplicate message {} for client {}", clientId, message);
        }
    }

    private void addMessage(String clientId, CacheMessage message) {
        logger.debug("Adding message {} for client {}", clientId, message);
        ClientQueue clientQueue = messages.get(clientId);
        if (clientQueue == null) {
            clientQueue = new ClientQueue();
            messages.put(clientId, clientQueue);
        }
        clientQueue.getQueue().addLast(message);
        clientQueue.getIds().add(message.getId());
    }

    private boolean hasMessage(String clientId, String messageId) {
        ClientQueue clientQueue = messages.get(clientId);
        return clientQueue != null && clientQueue.getIds().contains(messageId);
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, AtmosphereResource r) {
        String clientId = r.uuid();
        long now = System.nanoTime();

        List<Object> result = new ArrayList<Object>();

        ClientQueue clientQueue;
        synchronized (messages) {
            activeClients.put(clientId, now);
            clientQueue = messages.remove(clientId);
        }
        List<CacheMessage> clientMessages;
        if (clientQueue == null) {
            clientMessages = Collections.emptyList();
        } else {
            clientMessages = clientQueue.getQueue();
        }

        for (CacheMessage cacheMessage : clientMessages) {
            result.add(cacheMessage.getMessage());
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved for AtmosphereResource {} cached messages {}", r.uuid(), result);
            logger.debug("Available cached message {}", messages);
        }

        return result;
    }

    public void clearCache(String broadcasterId, AtmosphereResourceImpl r, CacheMessage message) {
        String clientId =  uuid(r);
        ClientQueue clientQueue;
        synchronized (messages) {
            clientQueue = messages.get(clientId);
            if (clientQueue != null) {
                logger.debug("Removing for AtmosphereResource {} cached message {}", r.uuid(), message.getMessage());
                clientQueue.getQueue().remove(message);
            }
        }
    }

    public Map<String, ClientQueue> messages() {
        return messages;
    }

    public Map<String, Long> activeClients() {
        return activeClients;
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    protected boolean inspect(Message m) {
        for (BroadcasterCacheInspector b : inspectors) {
              if (!b.inspect(m)) return false;
        }
        return true;
    }
}