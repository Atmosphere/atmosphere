/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.cpr;

import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.BroadcasterConfig.DefaultBroadcasterCache;
import org.atmosphere.di.InjectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

/**
 * {@link Broadcaster} implementation.
 * <p/>
 * Broadcast messages to suspended response using the caller's Thread.
 * This basic {@link Broadcaster} use an {@link java.util.concurrent.ExecutorService}
 * to broadcast message, hence the broadcast operation is asynchronous. Make sure
 * you block on {@link #broadcast(Object)}.get()} if you need synchronous operations.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultBroadcaster implements Broadcaster {

    private static final Logger logger = LoggerFactory.getLogger(DefaultBroadcaster.class);
    public static final String CACHED = DefaultBroadcaster.class.getName() + ".messagesCached";

    protected final ConcurrentLinkedQueue<AtmosphereResource<?, ?>> resources =
            new ConcurrentLinkedQueue<AtmosphereResource<?, ?>>();
    protected BroadcasterConfig bc;
    protected final BlockingQueue<Entry> messages = new LinkedBlockingQueue<Entry>();
    protected final BlockingQueue<AsyncWriteToken> asyncWriteQueue = new LinkedBlockingQueue<AsyncWriteToken>();

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean destroyed = new AtomicBoolean(false);

    protected SCOPE scope = SCOPE.APPLICATION;
    protected String name = DefaultBroadcaster.class.getSimpleName();
    protected final ConcurrentLinkedQueue<Entry> delayedBroadcast = new ConcurrentLinkedQueue<Entry>();
    protected final ConcurrentLinkedQueue<Entry> broadcastOnResume = new ConcurrentLinkedQueue<Entry>();
    protected final ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners = new ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener>();

    protected Future<?> notifierFuture;
    protected Future<?> asyncWriteFuture;
    protected BroadcasterCache broadcasterCache;

    private POLICY policy = POLICY.FIFO;
    private final AtomicLong maxSuspendResource = new AtomicLong(-1);
    private final AtomicBoolean requestScoped = new AtomicBoolean(false);
    private BroadcasterLifeCyclePolicy lifeCyclePolicy = new BroadcasterLifeCyclePolicy.Builder()
            .policy(BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER).build();
    private Future<?> currentLifecycleTask;
    protected URI uri;
    protected AtmosphereServlet.AtmosphereConfig config;
    private String initialId;

    public DefaultBroadcaster(String name, URI uri, AtmosphereServlet.AtmosphereConfig config) {
        this.name = name;
        this.uri = uri;
        this.config = config;

        broadcasterCache = new DefaultBroadcasterCache();
        bc = new BroadcasterConfig(AtmosphereServlet.broadcasterFilters, config);
    }

    public DefaultBroadcaster(String name, AtmosphereServlet.AtmosphereConfig config) {
        this(name, URI.create("http://localhost"), config);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy() {
        if (destroyed.get()) return;

        try {
            logger.debug("Broadcaster {} is being destroyed and cannot be re-used", getID());

            if (BroadcasterFactory.getDefault() != null) {
                BroadcasterFactory.getDefault().remove(this, this.getID());
            }

            if (currentLifecycleTask != null) {
                currentLifecycleTask.cancel(true);
            }
            started.set(false);
            destroyed.set(true);

            releaseExternalResources();
            if (notifierFuture != null) {
                notifierFuture.cancel(true);
            }

            if (asyncWriteFuture != null) {
                asyncWriteFuture.cancel(true);
            }

            if (bc != null) {
                bc.destroy();
            }

            if (broadcasterCache != null) {
                broadcasterCache.stop();
            }
            resources.clear();
            broadcastOnResume.clear();
            messages.clear();
            asyncWriteQueue.clear();
            delayedBroadcast.clear();
            broadcasterCache = null;
        } catch (Throwable t) {
            logger.error("Unexpected exception during Broadcaster destroy {}", getID(), t);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Collection<AtmosphereResource<?, ?>> getAtmosphereResources() {
        return Collections.unmodifiableCollection(resources);
    }

    /**
     * {@inheritDoc}
     */
    public void setScope(SCOPE scope) {
        this.scope = scope;
        if (scope != SCOPE.REQUEST) {
            return;
        }

        logger.debug("Changing broadcaster scope for {}. This broadcaster will be destroyed.", getID());

        synchronized (resources) {
            try {
                // Next, we need to create a new broadcaster per resource.
                for (AtmosphereResource<?, ?> resource : resources) {
                    Broadcaster b = BroadcasterFactory.getDefault()
                            .get(getClass(), getClass().getSimpleName() + "/" + UUID.randomUUID());

                    if (DefaultBroadcaster.class.isAssignableFrom(this.getClass())) {
                        BroadcasterCache cache = bc.getBroadcasterCache().getClass().newInstance();
                        InjectorProvider.getInjector().inject(cache);
                        DefaultBroadcaster.class.cast(b).broadcasterCache = cache;
                    }
                    resource.setBroadcaster(b);
                    if (resource.getAtmosphereResourceEvent().isSuspended()) {
                        b.addAtmosphereResource(resource);
                    }
                    logger.debug("Resource {} not using broadcaster {}", resource, b.getID());
                }


                destroy();
            } catch (Exception e) {
                logger.error("Failed to set request scope for current resources", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public SCOPE getScope() {
        return scope;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void setID(String id) {
        if (id == null) {
            id = getClass().getSimpleName() + "/" + UUID.randomUUID();
        }

        Broadcaster b = BroadcasterFactory.getDefault().lookup(this.getClass(), id);
        if (b != null && b.getScope() == SCOPE.REQUEST) {
            throw new IllegalStateException("Broadcaster ID already assigned to SCOPE.REQUEST. Cannot change the id");
        } else if (b != null) {
            return;
        }

        BroadcasterFactory.getDefault().remove(this, name);
        this.name = id;
        BroadcasterFactory.getDefault().add(this, name);
    }

    /**
     * {@inheritDoc}
     */
    public String getID() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    public void resumeAll() {
        synchronized (resources) {
            for (AtmosphereResource<?, ?> r : resources) {
                try {
                    r.resume();
                } catch (Throwable t) {
                    logger.trace("resumeAll", t);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseExternalResources() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBroadcasterLifeCyclePolicy(final BroadcasterLifeCyclePolicy lifeCyclePolicy) {
        this.lifeCyclePolicy = lifeCyclePolicy;
        if (currentLifecycleTask != null) {
            currentLifecycleTask.cancel(false);
        }

        if (lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE
                || lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY) {

            int time = lifeCyclePolicy.getTimeout();
            if (time == -1) {
                throw new IllegalStateException("BroadcasterLifeCyclePolicy time is not set");
            }

            final AtomicReference<Future<?>> ref = new AtomicReference<Future<?>>();
            currentLifecycleTask = bc.getScheduledExecutorService().scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        if (resources.isEmpty()) {
                            notifyEmptyListener();
                            notifyIdleListener();

                            if (lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE) {
                                releaseExternalResources();
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE policy to Broadcaster {}", getID());
                            } else {
                                destroy(false);
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_DESTROY policy to Broadcaster {}", getID());
                            }
                        } else if (lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME) {
                            destroy(true);
                            logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_RESUME policy to Broadcaster {}", getID());
                        }
                    } catch (Throwable t) {
                        if (destroyed.get()) {
                            logger.trace("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        } else {
                            logger.warn("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        }
                    }
                }

                void destroy(boolean resume) {
                    notifyDestroyListener();

                    if (resume) {
                        logger.info("All AtmosphereResource will now be resumed from Broadcaster {}", getID());
                        resumeAll();
                    }

                    DefaultBroadcaster.this.destroy();
                    /**
                     * The value may be null if the timeout is too low. Hopefully next execution will
                     * cancel the task properly.
                     */
                    if (ref.get() != null) {
                        currentLifecycleTask.cancel(true);
                    }
                }

            }, time, time, lifeCyclePolicy.getTimeUnit());
            ref.set(currentLifecycleTask);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.add(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeBroadcasterLifeCyclePolicyListener(BroadcasterLifeCyclePolicyListener b) {
        lifeCycleListeners.remove(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    public static final class Entry {

        public Object message;
        public Object multipleAtmoResources;
        public BroadcasterFuture<?> future;
        public boolean writeLocally;
        public Object originalMessage;

        public Entry(Object message, Object multipleAtmoResources, BroadcasterFuture<?> future, Object originalMessage) {
            this.message = message;
            this.multipleAtmoResources = multipleAtmoResources;
            this.future = future;
            this.writeLocally = true;
            this.originalMessage = originalMessage;
        }

        public Entry(Object message, Object multipleAtmoResources, BroadcasterFuture<?> future, boolean writeLocally) {
            this.message = message;
            this.multipleAtmoResources = multipleAtmoResources;
            this.future = future;
            this.writeLocally = writeLocally;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "message=" + message +
                    ", multipleAtmoResources=" + multipleAtmoResources +
                    ", future=" + future +
                    '}';
        }
    }

    protected Runnable getBroadcastHandler() {
        return new Runnable() {
            public void run() {
                Entry msg = null;
                while (started.get()) {
                    try {
                        msg = messages.take();
                        push(msg);
                    } catch (Throwable ex) {
                        if (!started.get() || destroyed.get()) {
                            logger.trace("Failed to submit broadcast handler runnable to broadcast executor service on shutdown", ex);
                        } else {
                            logger.warn("This message {} will be lost", msg);
                            logger.debug("Failed to submit broadcast handler runnable to broadcast executor service", ex);
                        }
                    }
                }
            }
        };
    }

    protected void start() {
        if (!started.getAndSet(true)) {
            setID(name);
            broadcasterCache = bc.getBroadcasterCache();
            broadcasterCache.start();

            notifierFuture = bc.getExecutorService().submit(getBroadcastHandler());
            asyncWriteFuture = bc.getAsyncWriteService().submit(getAsyncWriteHandler());
        }
    }

    protected void push(Entry entry) {

        if (destroyed.get()) {
            return;
        }

        String prevMessage = entry.message.toString();
        if (!delayedBroadcast.isEmpty()) {
            Iterator<Entry> i = delayedBroadcast.iterator();
            StringBuilder b = new StringBuilder();
            while (i.hasNext()) {
                Entry e = i.next();
                e.future.cancel(true);
                try {
                    // Append so we do a single flush
                    if (e.message instanceof String
                            && entry.message instanceof String) {
                        b.append(e.message);
                    } else {
                        push(e);
                    }
                } finally {
                    i.remove();
                }
            }

            if (b.length() > 0) {
                entry.message = b.append(entry.message).toString();
            }
        }

        if (resources.isEmpty()) {
            trackBroadcastMessage(null, entry.message);
            if (entry.future != null) {
                entry.future.done();
            }
            return;
        }

        Object finalMsg = translate(entry.message);
        entry.message = finalMsg;
        try {
            if (entry.multipleAtmoResources == null) {
                for (AtmosphereResource<?, ?> r : resources) {
                    finalMsg = perRequestFilter(r, entry);

                    if (finalMsg == null) {
                        logger.debug("Skipping broadcast delivery resource {} ", r);
                        continue;
                    }

                    if (entry.writeLocally) {
                        queueWriteIO(r, finalMsg, entry);
                    }
                }
            } else if (entry.multipleAtmoResources instanceof AtmosphereResource<?, ?>) {
                finalMsg = perRequestFilter((AtmosphereResource<?, ?>) entry.multipleAtmoResources, entry);

                if (finalMsg == null) {
                    logger.debug("Skipping broadcast delivery resource {} ", entry.multipleAtmoResources);
                    return;
                }

                if (entry.writeLocally) {
                    queueWriteIO((AtmosphereResource<?, ?>) entry.multipleAtmoResources, finalMsg, entry);
                }
            } else if (entry.multipleAtmoResources instanceof Set) {
                Set<AtmosphereResource<?, ?>> sub = (Set<AtmosphereResource<?, ?>>) entry.multipleAtmoResources;
                for (AtmosphereResource<?, ?> r : sub) {
                    finalMsg = perRequestFilter(r, entry);

                    if (finalMsg == null) {
                        logger.debug("Skipping broadcast delivery resource {} ", r);
                        continue;
                    }

                    if (entry.writeLocally) {
                        queueWriteIO(r, finalMsg, entry);
                    }
                }
            }
            entry.message = prevMessage;
        } catch (InterruptedException ex) {
            logger.debug(ex.getMessage(), ex);
        }
    }

    protected void queueWriteIO(AtmosphereResource<?, ?> r, Object finalMsg, Entry entry) throws InterruptedException {
        asyncWriteQueue.put(new AsyncWriteToken(r, finalMsg, entry.future));
    }

    protected Object perRequestFilter(AtmosphereResource<?, ?> r, Entry msg) {
        Object finalMsg = msg.message;

        if (AtmosphereResourceImpl.class.isAssignableFrom(r.getClass())) {
            if (AtmosphereResourceImpl.class.cast(r).isInScope()) {
                if (r.getRequest() instanceof HttpServletRequest && bc.hasPerRequestFilters()) {
                    Object message = msg.originalMessage;
                    BroadcastAction a = bc.filter((HttpServletRequest) r.getRequest(), (HttpServletResponse) r.getResponse(), message);
                    if (a.action() == BroadcastAction.ACTION.ABORT) {
                        return null;
                    }
                    if (a.message() != msg.originalMessage) {
                        finalMsg = a.message();
                    }
                }
                trackBroadcastMessage(r, finalMsg);
            } else {
                // The resource is no longer valid.
                removeAtmosphereResource(r);
                BroadcasterFactory.getDefault().removeAllAtmosphereResource(r);
            }

        }
        return finalMsg;
    }

    private Object translate(Object msg) {
        if (Callable.class.isAssignableFrom(msg.getClass())) {
            try {
                return Callable.class.cast(msg).call();
            } catch (Exception e) {
                logger.error("failed to cast message: " + msg, e);
            }
        }
        return msg;
    }

    protected void executeAsyncWrite(final AtmosphereResource<?, ?> resource, final Object msg, final BroadcasterFuture future) {
        boolean notifyListeners = true;
        try {
            final AtmosphereResourceEventImpl event = (AtmosphereResourceEventImpl) resource.getAtmosphereResourceEvent();

            // Any of these conditions stop the write operations
            boolean isVoid = event.isCancelled() || event.isResumedOnTimeout() || event.isResuming() || !event.isSuspended();
            if (isVoid) {
                logger.debug("Resource {} has been already processed", event);
                notifyListeners = false;
                return;
            }

            event.setMessage(msg);

            try {
                // Check again to make sure we are suspended
                if (event.isSuspended()) {
                    HttpServletRequest.class.cast(resource.getRequest())
                            .setAttribute(MAX_INACTIVE, System.currentTimeMillis());
                } else {
                    // The Request/Response associated with the AtmosphereResource has already been written and commited
                    removeAtmosphereResource(resource);
                    BroadcasterFactory.getDefault().removeAllAtmosphereResource(resource);
                    return;
                }
            } catch (Exception t) {
                // Shield us from any corrupted Request
                logger.debug("Preventing corruption of a recycled request: resource" + resource, event);
                removeAtmosphereResource(resource);
                BroadcasterFactory.getDefault().removeAllAtmosphereResource(resource);

                event.setCancelled(true);
                event.setThrowable(t);
                return;
            }

            broadcast(resource, event);
        } finally {
            if (notifyListeners && resource instanceof AtmosphereEventLifecycle) {
                ((AtmosphereEventLifecycle) resource).notifyListeners();
            }

            if (future != null) {
                future.done();
            }
        }
    }

    protected Runnable getAsyncWriteHandler() {
        return new Runnable() {
            public void run() {
                AsyncWriteToken token = null;
                try {
                    token = asyncWriteQueue.take();
                    synchronized (token.resource) {

                        // We want this thread to wait for the write operation to happens to kept the order
                        bc.getAsyncWriteService().submit(this);

                        // If the resource is no longer in scope, skip the processing.
                        if (AtmosphereResourceImpl.class.cast(token.resource).isInScope()) {
                            executeAsyncWrite(token.resource, token.msg, token.future);
                        }
                    }
                } catch (Throwable ex) {
                    if (!started.get() || destroyed.get()) {
                        logger.trace("Failed to submit async write task on shutdown", ex);
                    } else {
                        logger.warn("Failed to write {}", token);
                        logger.debug("Failed to submit async write task", ex);
                    }
                }
            }
        };
    }

    protected void checkCachedAndPush(final AtmosphereResource<?, ?> r, final AtmosphereResourceEvent e) {
        retrieveTrackedBroadcast(r, e);
        if (e.getMessage() instanceof List && !((List) e.getMessage()).isEmpty()) {
            HttpServletRequest.class.cast(r.getRequest()).setAttribute(CACHED, "true");
            // Must make sure execute only one thread
            synchronized (r) {
                broadcast(r, e);
            }
        }
    }

    protected boolean retrieveTrackedBroadcast(final AtmosphereResource<?, ?> r, final AtmosphereResourceEvent e) {
        List<?> missedMsg = broadcasterCache.retrieveFromCache(r);
        if (!missedMsg.isEmpty()) {
            e.setMessage(missedMsg);
            return true;
        }
        return false;
    }

    protected void trackBroadcastMessage(final AtmosphereResource<?, ?> r, Object msg) {
        broadcasterCache.addToCache(r, msg);
    }

    protected void broadcast(final AtmosphereResource<?, ?> r, final AtmosphereResourceEvent e) {
        try {
            r.getAtmosphereHandler().onStateChange(e);
        } catch (Throwable t) {
            onException(t, r);
        }
    }

    protected void onException(Throwable t, final AtmosphereResource<?, ?> r) {
        logger.debug("onException()", t);

        // Remove to prevent other broadcast to re-use it.
        removeAtmosphereResource(r);

        final AtmosphereResourceEventImpl event = (AtmosphereResourceEventImpl) r.getAtmosphereResourceEvent();
        event.setThrowable(t);

        if (r instanceof AtmosphereEventLifecycle) {
            ((AtmosphereEventLifecycle) r)
                    .notifyListeners(event);
            ((AtmosphereEventLifecycle) r).removeEventListeners();
        }

        /**
         * Make sure we resume the connection on every IOException.
         */
        bc.getAsyncWriteService().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    r.resume();
                } catch (Throwable t) {
                    logger.warn("Was unable to resume a corrupted AtmosphereResource {}", r);
                    logger.warn("Cause", t);
                }
            }
        });

    }

    @Override
    public void setSuspendPolicy(long maxSuspendResource, POLICY policy) {
        this.maxSuspendResource.set(maxSuspendResource);
        this.policy = policy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, null, f, msg));
        return f;
    }

    /**
     * Invoke the {@link BroadcastFilter}
     *
     * @param msg
     * @return
     */
    protected Object filter(Object msg) {
        BroadcastAction a = bc.filter(msg);
        if (a.action() == BroadcastAction.ACTION.ABORT || msg == null)
            return null;
        else
            return a.message();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, AtmosphereResource<?, ?> r) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, r, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcastOnResume(T msg) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        broadcastOnResume.offer(new Entry(newMsg, null, f, msg));
        return f;
    }

    protected void broadcastOnResume(AtmosphereResource<?, ?> r) {
        Iterator<Entry> i = broadcastOnResume.iterator();
        while (i.hasNext()) {
            Entry e = i.next();
            e.multipleAtmoResources = r;
            push(e);
        }

        if (resources.isEmpty()) {
            broadcastOnResume.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg, Set<AtmosphereResource<?, ?>> subset) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        messages.offer(new Entry(newMsg, subset, f, msg));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource<?, ?> addAtmosphereResource(AtmosphereResource<?, ?> r) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return r;
        }

        start();
        if (scope == SCOPE.REQUEST && requestScoped.getAndSet(true)) {
            throw new IllegalStateException("Broadcaster " + this
                    + " cannot be used as its scope is set to REQUEST");
        }

        // To avoid excessive synchronization, we allow resources.size() to get larger that maxSuspendResource
        if (maxSuspendResource.get() > 0 && resources.size() >= maxSuspendResource.get()) {
            // Resume the first in.
            if (policy == POLICY.FIFO) {
                // TODO handle null return from poll()
                AtmosphereResource<?, ?> resource = resources.poll();
                try {
                    logger.warn("Too many resource. Forcing resume of {} ", resource);
                    resource.resume();
                } catch (Throwable t) {
                    logger.warn("failed to resume resource {} ", resource, t);
                }
            } else if (policy == POLICY.REJECT) {
                throw new RejectedExecutionException(String.format("Maximum suspended AtmosphereResources %s", maxSuspendResource));
            }
        }

        if (resources.contains(r)) {
            return r;
        }

        // Re-add yourself
        if (resources.isEmpty()) {
            BroadcasterFactory.getDefault().add(this, name);
        }

        resources.add(r);
        checkCachedAndPush(r, r.getAtmosphereResourceEvent());
        return r;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource<?, ?> removeAtmosphereResource(AtmosphereResource r) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return r;
        }

        // Prevent two thread to mix operation
        synchronized (resources) {
            if (!resources.contains(r)) {
                return null;
            }
            resources.remove(r);

            // Will help preventing OOM.
            if (resources.isEmpty()) {
                notifyEmptyListener();
                if (scope != SCOPE.REQUEST && lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY) {
                    releaseExternalResources();
                } else if (lifeCyclePolicy.getLifeCyclePolicy() == BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY) {
                    notifyDestroyListener();
                    BroadcasterFactory.getDefault().remove(this, name);
                    destroy();
                }
            }
        }
        return r;
    }

    private void notifyIdleListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onIdle();
        }
    }

    private void notifyDestroyListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onDestroy();
        }
    }

    private void notifyEmptyListener() {
        for (BroadcasterLifeCyclePolicyListener b : lifeCycleListeners) {
            b.onEmpty();
        }
    }

    /**
     * Set the {@link BroadcasterConfig} instance.
     *
     * @param bc
     */
    @Override
    public void setBroadcasterConfig(BroadcasterConfig bc) {
        this.bc = bc;
    }

    /**
     * Return the current {@link BroadcasterConfig}
     *
     * @return the current {@link BroadcasterConfig}
     */
    public BroadcasterConfig getBroadcasterConfig() {
        return bc;
    }

    /**
     * {@inheritDoc}
     */
    public <T> Future<T> delayBroadcast(T o) {
        return delayBroadcast(o, 0, null);
    }

    /**
     * {@inheritDoc}
     */
    public <T> Future<T> delayBroadcast(final T o, long delay, TimeUnit t) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        final Object msg = filter(o);
        if (msg == null) return null;

        final BroadcasterFuture<Object> future = new BroadcasterFuture<Object>(msg);
        final Entry e = new Entry(msg, null, future, o);
        Future<T> f;
        if (delay > 0) {
            f = bc.getScheduledExecutorService().schedule(new Callable<T>() {

                public T call() throws Exception {
                    delayedBroadcast.remove(e);
                    if (Callable.class.isAssignableFrom(o.getClass())) {
                        try {
                            Object r = Callable.class.cast(o).call();
                            final Object msg = filter(r);
                            if (msg != null) {
                                Entry entry = new Entry(msg, null, null, r);
                                push(entry);
                            }
                            return (T) msg;
                        } catch (Exception e1) {
                            logger.error("", e);
                        }
                    }

                    final Object msg = filter(o);
                    final Entry e = new Entry(msg, null, null, o);
                    push(e);
                    return (T) msg;
                }
            }, delay, t);

            e.future = new BroadcasterFuture<Object>(f, msg);
        }
        delayedBroadcast.offer(e);
        return future;
    }

    /**
     * {@inheritDoc}
     */
    public Future<?> scheduleFixedBroadcast(final Object o, long period, TimeUnit t) {
        return scheduleFixedBroadcast(o, 0, period, t);
    }

    /**
     * {@inheritDoc}
     */
    public Future<?> scheduleFixedBroadcast(final Object o, long waitFor, long period, TimeUnit t) {

        if (destroyed.get()) {
            logger.error("This Broadcaster has been destroyed and cannot be used");
            return null;
        }

        start();
        if (period == 0 || t == null) {
            return null;
        }

        final Object msg = filter(o);
        if (msg == null) return null;

        return bc.getScheduledExecutorService().scheduleWithFixedDelay(new Runnable() {
            public void run() {
                if (Callable.class.isAssignableFrom(o.getClass())) {
                    try {
                        Object r = Callable.class.cast(o).call();
                        final Object msg = filter(r);
                        if (msg != null) {
                            Entry entry = new Entry(msg, null, null, r);
                            push(entry);
                        }
                        return;
                    } catch (Exception e) {
                        logger.error("", e);
                    }
                }
                final Object msg = filter(o);
                final Entry e = new Entry(msg, null, null, o);
                push(e);
            }
        }, waitFor, period, t);
    }

    public String toString() {
        return new StringBuilder(this.getClass().getName()).append("@").append(this.hashCode()).append("\n")
                .append("\tName: ").append(name).append("\n")
                .append("\tScope: ").append(scope).append("\n")
                .append("\tBroasdcasterCache ").append(broadcasterCache).append("\n")
                .append("\tAtmosphereResource: ").append(resources.size()).append("\n")
                .toString();
    }

    private final static class AsyncWriteToken {

        final AtmosphereResource<?, ?> resource;
        final Object msg;
        final BroadcasterFuture future;

        public AsyncWriteToken(AtmosphereResource<?, ?> resource, Object msg, BroadcasterFuture future) {
            this.resource = resource;
            this.msg = msg;
            this.future = future;
        }

        @Override
        public String toString() {
            return "AsyncWriteToken{" +
                    "resource=" + resource +
                    ", msg=" + msg +
                    ", future=" + future +
                    '}';
        }
    }

}
