package org.atmosphere.cache;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterCacheListener;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.UUIDProvider;
import org.redisson.Config;
import org.redisson.Redisson;
import org.redisson.core.RMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_CLIENT_IDLETIME;
import static org.atmosphere.cpr.ApplicationConfig.UUIDBROADCASTERCACHE_IDLE_CACHE_INTERVAL;

public class UUIDRedissonBroadcasterCache implements BroadcasterCache {

    private final static Logger logger = LoggerFactory.getLogger(UUIDBroadcasterCache.class);
    private static final String REDIS_SERVER = UUIDRedissonBroadcasterCache.class.getName() + ".server";
    private Redisson redisson;
    private Config redisConfig = new Config();
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();
    private ScheduledFuture scheduledFuture;
    protected ScheduledExecutorService taskScheduler;
    private long clientIdleTime = TimeUnit.SECONDS.toMillis(60); // 1 minutes
    private long invalidateCacheInterval = TimeUnit.SECONDS.toMillis(30); // 30 seconds
    private boolean shared = true;
    protected final List<Object> emptyList = Collections.<Object>emptyList();
    protected final List<BroadcasterCacheListener> listeners = new LinkedList<BroadcasterCacheListener>();
    private UUIDProvider uuidProvider;

    private enum TypeOfTransaction {GET, PUT, REMOVE, GET_ALL}

    /**
     * This class wraps all messages to be delivered to a client. The class is thread safe to be accessed in a
     * concurrent context.
     */
    public final static class ClientQueue implements Serializable {
        private static final long serialVersionUID = -126253550299206646L;

        private final ConcurrentLinkedQueue<CacheMessage> queue = new ConcurrentLinkedQueue<CacheMessage>();
        private final Set<String> ids = Collections.synchronizedSet(new HashSet<String>());

        public ConcurrentLinkedQueue<CacheMessage> getQueue() {
            return queue;
        }

        public Set<String> getIds() {
            return ids;
        }

        @Override
        public String toString() {
            return queue.toString();
        }
    }

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

        URI uri;
        if (config.getServletConfig().getInitParameter(REDIS_SERVER) != null) {
            uri = URI.create(config.getServletConfig().getInitParameter(REDIS_SERVER));
        } else {
            throw new NullPointerException("uri cannot be null");
        }

        redisConfig.useSingleServer().setAddress(uri.getHost() + ":" + uri.getPort());
        redisConfig.useSingleServer().setDatabase(1);

        redisson = Redisson.create(redisConfig);

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
        redisson.delete("messages");
        redisson.delete("activeClients");
        redisson.shutdown();
        emptyList.clear();
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
            logger.trace("Active clients {}", getActiveClients(TypeOfTransaction.GET, "", 0L));
        }

        String messageId = uuidProvider.generateUuid();
        boolean cache = true;
        if (!inspect(message)) {
            cache = false;
        }

        CacheMessage cacheMessage = new CacheMessage(messageId, message.message(), uuid);
        if (cache) {
            if (uuid.equals(NULL)) {
                //no clients are connected right now, caching message for all active clients
                for (Map.Entry<String, Long> entry : getActiveClients(TypeOfTransaction.GET, "", 0L).entrySet()) {
                    addMessageIfNotExists(broadcasterId, entry.getKey(), cacheMessage);
                }
            } else {
                cacheCandidate(broadcasterId, uuid);
                addMessageIfNotExists(broadcasterId, uuid, cacheMessage);
            }
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, String uuid) {

        List<Object> result = new ArrayList<Object>();

        ClientQueue clientQueue;
        cacheCandidate(broadcasterId, uuid);
        clientQueue = getClient(TypeOfTransaction.REMOVE, uuid);
        ConcurrentLinkedQueue<CacheMessage> clientMessages;
        if (clientQueue != null) {
            clientMessages = clientQueue.getQueue();

            for (CacheMessage cacheMessage : clientMessages) {
                result.add(cacheMessage.getMessage());
            }
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Retrieved for AtmosphereResource {} cached messages {}", uuid, result);
            logger.trace("Available cached message {}", getMessages(TypeOfTransaction.GET_ALL,""));
        }

        return result;
    }

    @Override
    public BroadcasterCache clearCache(String broadcasterId, String uuid, CacheMessage message) {
        ClientQueue clientQueue;
        clientQueue = getClient(TypeOfTransaction.GET, uuid);
        if (clientQueue != null) {
            logger.trace("Removing for AtmosphereResource {} cached message {}", uuid, message.getMessage());
            notifyRemoveCache(broadcasterId, message);
            clientQueue.getQueue().remove(message);
            clientQueue.getIds().remove(message.getId());
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

    private void addMessageIfNotExists(String broadcasterId, String clientId, CacheMessage message) {
        if (!hasMessage(clientId, message.getId())) {
            addMessage(broadcasterId, clientId, message);
        } else {
            logger.debug("Duplicate message {} for client {}", message, clientId);
        }
    }

    private void addMessage(String broadcasterId, String clientId, CacheMessage message) {
        ClientQueue clientQueue = getClient(TypeOfTransaction.GET,clientId);
        if (clientQueue == null) {
            clientQueue = new ClientQueue();
            // Make sure the client is not in the process of being invalidated
            if (getClient(TypeOfTransaction.GET,clientId) != null) {
                actionMessages(TypeOfTransaction.GET, clientId, clientQueue);
            } else {
                // The entry has been invalidated
                logger.debug("Client {} is no longer active. Not caching message {}}", clientId, message);
                return;
            }
        }
        notifyAddCache(broadcasterId, message);
        clientQueue.getQueue().offer(message);
        clientQueue.getIds().add(message.getId());
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

    private boolean hasMessage(String clientId, String messageId) {
        ClientQueue clientQueue = getClient(TypeOfTransaction.GET,clientId);
        return clientQueue != null && clientQueue.getIds().contains(messageId);
    }

    public ClientQueue getClient(TypeOfTransaction type, String string) {
        Map<String, ClientQueue> messages = redisson.getMap("messages");
        ClientQueue clientQueue;
        if (type == TypeOfTransaction.REMOVE) {
            clientQueue = messages.remove(string);
        }else {
            clientQueue = messages.get(string);
        }
        return clientQueue;
    }

    public void actionMessages(TypeOfTransaction type, String string, ClientQueue clientQueue) {
        Map<String, ClientQueue> messages = redisson.getMap("messages");
        if (type == TypeOfTransaction.PUT) {
            messages.put(string, clientQueue);
        } else if (type == TypeOfTransaction.REMOVE) {
            messages.remove(string);
        }
    }

    public Map<String, ClientQueue> getMessages(TypeOfTransaction type, String string) {
        Map<String, ClientQueue> messages = redisson.getMap("messages");
        if (type == TypeOfTransaction.GET) {
            messages.get(string);
        }
        return messages;
    }

    public Map<String, Long> getActiveClients(TypeOfTransaction type, String string, Long number) {
        Map<String, Long> messages = redisson.getMap("activeClients");
        if (type == TypeOfTransaction.PUT) {
            messages.put(string, number);
        } else if (type == TypeOfTransaction.REMOVE) {
            messages.remove(string);
        }
        return messages;
    }

    protected boolean inspect(BroadcastMessage m) {
        for (BroadcasterCacheInspector b : inspectors) {
            if (!b.inspect(m)) return false;
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

        Map<String, Long> activeClients = getActiveClients(TypeOfTransaction.GET, "", 0L);
        Set<String> inactiveClients = new HashSet<String>();
        for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > clientIdleTime) {
                logger.trace("Invalidate client {}", entry.getKey());
                inactiveClients.add(entry.getKey());
            }
        }

        for (String clientId : inactiveClients) {
            activeClients.remove(clientId);
            getClient(TypeOfTransaction.REMOVE, clientId);
        }

        for (String msg : getMessages(TypeOfTransaction.GET_ALL,"").keySet()) {
            if (!activeClients.containsKey(msg)) {
                actionMessages(TypeOfTransaction.REMOVE, msg, new ClientQueue());
            }
        }
    }

    @Override
    public BroadcasterCache excludeFromCache(String broadcasterId, AtmosphereResource r) {
        getActiveClients(TypeOfTransaction.REMOVE,r.uuid(),0L);
        return this;
    }

    @Override
    public BroadcasterCache cacheCandidate(String broadcasterId, String uuid) {
        long now = System.currentTimeMillis();
        getActiveClients(TypeOfTransaction.PUT,uuid, now);
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

