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

import com.sun.grizzly.util.LoggerUtils;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.cpr.BroadcasterConfig.DefaultBroadcasterCache;
import org.atmosphere.di.InjectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
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
import java.util.logging.Level;

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

    protected final ConcurrentLinkedQueue<AtmosphereResource<?, ?>> resources =
            new ConcurrentLinkedQueue<AtmosphereResource<?, ?>>();
    protected BroadcasterConfig bc;
    protected final BlockingQueue<Entry> messages = new LinkedBlockingQueue<Entry>();
    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean destroyed = new AtomicBoolean(false);

    protected SCOPE scope = SCOPE.APPLICATION;
    protected String name = DefaultBroadcaster.class.getSimpleName();
    protected final ConcurrentLinkedQueue<Entry> delayedBroadcast = new ConcurrentLinkedQueue<Entry>();
    protected final ConcurrentLinkedQueue<Entry> broadcastOnResume = new ConcurrentLinkedQueue<Entry>();

    protected Future<?> notifierFuture;
    protected BroadcasterCache broadcasterCache;

    private POLICY policy = POLICY.FIFO;
    private long maxSuspendResource = -1;
    private final AtomicBoolean requestScoped = new AtomicBoolean(false);

    public DefaultBroadcaster() {
        this(DefaultBroadcaster.class.getSimpleName());
    }

    public DefaultBroadcaster(String name) {
        this.name = name;
        broadcasterCache = new DefaultBroadcasterCache();
        bc = new BroadcasterConfig(AtmosphereServlet.broadcasterFilters, null);
        setID(name);
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (notifierFuture != null) {
            notifierFuture.cancel(true);
        }

        if (bc != null && scope != SCOPE.REQUEST) {
            bc.destroy();
        }

        if (broadcasterCache != null) {
            broadcasterCache.stop();
        }
        resources.clear();
        broadcastOnResume.clear();
        messages.clear();
        delayedBroadcast.clear();
        broadcasterCache = null;
        started.set(false);
        destroyed.set(true);
        if (BroadcasterFactory.getDefault() != null) {
            BroadcasterFactory.getDefault().remove(this, name);
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

        try {
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
            }

            if (!resources.isEmpty()) {
                destroy();
            }
        }
        catch (Exception e) {
            logger.error("failed to set request scope for current resources", e);
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
    public void setID(String id) {
        Broadcaster b = BroadcasterFactory.getDefault().lookup(this.getClass(), id);
        if (b != null && b.getScope() == SCOPE.REQUEST) {
            throw new IllegalStateException("Broadcaster ID already assigned to SCOPE.REQUEST. Cannot change the id");
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
        for (AtmosphereResource<?, ?> r : resources) {
            r.resume();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseExternalResources() {
    }

    public class Entry {

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
                try {
                    msg = messages.take();
                    // Leader/follower
                    bc.getExecutorService().submit(this);
                    push(msg);
                }
                catch (Throwable ex) {
                    // Catch all exception to avoid killing this thread.
                    // What if the Throwable is OOME?
                    logger.error("failed to submit broadcast handler runnable to broadcast executor service", ex);
                }
                finally {
                    if (msg != null) {
                        // TODO dubious logic, future is always instance of Broadcaster future
                        if (msg.future instanceof BroadcasterFuture) {
                            msg.future.done();
                        }
                        else {
                            msg.future.cancel(true);
                        }
                    }
                }
            }
        };
    }

    protected void start() {
        if (!started.getAndSet(true)) {

            broadcasterCache = bc.getBroadcasterCache();
            broadcasterCache.start();

            notifierFuture = bc.getExecutorService().submit(getBroadcastHandler());
        }
    }

    protected void push(Entry msg) {
        String prevMessage = msg.message.toString();
        if (!delayedBroadcast.isEmpty()) {
            Iterator<Entry> i = delayedBroadcast.iterator();
            StringBuilder b = new StringBuilder();
            while (i.hasNext()) {
                Entry e = i.next();
                // TODO dubious logic, future is always instance of Broadcaster future
                if (!(e.future instanceof BroadcasterFuture)) {
                    e.future.cancel(true);
                }
                try {
                    // Append so we do a single flush
                    if (e.message instanceof String
                            && msg.message instanceof String) {
                        b.append(e.message);
                    } else {
                        push(e);
                    }
                } finally {
                    i.remove();
                    // TODO dubious logic, future is always instance of Broadcaster future
                    if (e.future instanceof BroadcasterFuture) {
                        e.future.done();
                    }
                }
            }
            if (b.length() > 0) {
                msg.message = b.append(msg.message).toString();
            }
        }

        if (resources.isEmpty()) {
            trackBroadcastMessage(null, msg.message);
        }

        Object finalMsg = translate(msg.message);
        msg.message = finalMsg;

        if (msg.multipleAtmoResources == null) {
            for (AtmosphereResource<?, ?> r : resources) {
                finalMsg = perRequestFilter(r, msg);
                if (msg.writeLocally) {
                    push(r, finalMsg);
                }
            }                                                                                                                                                                               
        } else if (msg.multipleAtmoResources instanceof AtmosphereResource<?, ?>) {
            finalMsg = perRequestFilter((AtmosphereResource<?, ?>) msg.multipleAtmoResources, msg);

            if (msg.writeLocally) {
                push((AtmosphereResource<?, ?>) msg.multipleAtmoResources, finalMsg);
            }
        } else if (msg.multipleAtmoResources instanceof Set) {
            Set<AtmosphereResource<?, ?>> sub = (Set<AtmosphereResource<?, ?>>) msg.multipleAtmoResources;
            for (AtmosphereResource<?, ?> r : sub) {
                finalMsg = perRequestFilter(r, msg);
                if (msg.writeLocally) {
                    push(r, finalMsg);
                }
            }
        }
        msg.message = prevMessage;
    }

    protected Object perRequestFilter(AtmosphereResource<?, ?> r, Entry msg) {
        Object finalMsg = msg.message;
        if (r.getRequest() instanceof HttpServletRequest && bc.hasPerRequestFilters()) {
            Object message = msg.originalMessage;
            BroadcastAction a  = bc.filter( (HttpServletRequest) r.getRequest(), (HttpServletResponse) r.getResponse(), message);
            if (a.action() == BroadcastAction.ACTION.ABORT || a.message() != null) {
               finalMsg = a.message();   
            }
        }
               
        trackBroadcastMessage(r, finalMsg);
        return finalMsg;
    }

    private Object translate(Object msg) {
        if (Callable.class.isAssignableFrom(msg.getClass())) {
            try {
                return  Callable.class.cast(msg).call();
            } catch (Exception e) {
                logger.error("failed to cast message: " + msg, e);
            }
        }
        return msg;
    }

    protected void push(final AtmosphereResource<?, ?> resource, final Object msg) {

        synchronized (resource) {
            if (resource.getAtmosphereResourceEvent().isCancelled()) {
                return;
            }

            final AtmosphereResourceEvent event = resource.getAtmosphereResourceEvent();
            event.setMessage(msg);

            if (resource.getAtmosphereResourceEvent() != null && !resource.getAtmosphereResourceEvent().isCancelled()
                    && HttpServletRequest.class.isAssignableFrom(resource.getRequest().getClass())) {
                try {
                    HttpServletRequest.class.cast(resource.getRequest())
                            .setAttribute(CometSupport.MAX_INACTIVE, System.currentTimeMillis());
                }
                catch (Exception t) {
                    // Shield us from any corrupted Request
                    logger.warn("Preventing corruption of a recycled request: resource" + resource, event);
                    resources.remove(resource);
                    return;
                }
            }

            bc.getAsyncWriteService().execute(new Runnable(){
                @Override
                public void run() {
                    broadcast(resource, event);
                    if (resource instanceof AtmosphereEventLifecycle) {
                        ((AtmosphereEventLifecycle) resource).notifyListeners();
                    }
                }
            });
        }
    }

    protected void checkCachedAndPush(final AtmosphereResource<?, ?> r, final AtmosphereResourceEvent e) {
        retrieveTrackedBroadcast(r, e);
        if (e.getMessage() instanceof List && !((List) e.getMessage()).isEmpty()) {
            broadcast(r, e);
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
            r.getAtmosphereConfig().getAtmosphereHandler(this).onStateChange(e);
        } catch (IOException ex) {
            if (AtmosphereResourceImpl.class.isAssignableFrom(r.getClass())) {
                AtmosphereResourceImpl.class.cast(r).notifyListeners(e);
            }
            onException(ex, r);
        } catch (RuntimeException ex) {
            if (AtmosphereResourceImpl.class.isAssignableFrom(r.getClass())) {
                AtmosphereResourceImpl.class.cast(r).notifyListeners(e);
            }
            onException(ex, r);
        }
    }

    protected void onException(Throwable t, final AtmosphereResource<?, ?> r) {
        logger.debug("onException()", t);

        if (r instanceof AtmosphereEventLifecycle) {
            ((AtmosphereEventLifecycle) r)
                    .notifyListeners(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, true, false, t));
            ((AtmosphereEventLifecycle) r).removeEventListeners();
        }

        /**
         * Make sure we resume the connection on every IOException.
         */
        bc.getAsyncWriteService().execute( new Runnable()
        {
            @Override
            public void run()
            {
                r.resume();
            }
        } );

    }

    @Override
    public void setSuspendPolicy(long maxSuspendResource, POLICY policy) {
        this.maxSuspendResource = maxSuspendResource;
        this.policy = policy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> Future<T> broadcast(T msg) {

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

        start();
        Object newMsg = filter(msg);
        if (newMsg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(newMsg);
        broadcastOnResume.offer(new Entry(newMsg, null, f, msg));
        return f;
    }

    protected void broadcastOnResume(AtmosphereResource<?,?> r){
        // That's suck.
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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

        if (scope == SCOPE.REQUEST && requestScoped.getAndSet(true)) {
            throw new IllegalStateException("Broadcaster " + this
                    + " cannot be used as its scope is set to REQUEST");
        }

        if (maxSuspendResource > 0 && resources.size() == maxSuspendResource) {
            // Resume the first in.
            if (policy == POLICY.FIFO) {
                // TODO handle null return from poll()
                AtmosphereResource<?, ?> resource = resources.poll();
                try {
                    resource.resume();
                }
                catch (Throwable t) {
                    logger.warn("failed to resume resource: " + resource, t);
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
        if (!resources.contains(r)) {
            return null;
        }
        resources.remove(r);

        // Will help preventing OOM. Here we do not call destroy() as application may still have reference to
        // this broadcaster.
        if (resources.isEmpty()) {
            BroadcasterFactory.getDefault().remove(this, name);
            this.releaseExternalResources();
        }
        return r;
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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

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

        if (destroyed.get()) throw new IllegalStateException("This Broadcaster has been destroyed and cannot be used");

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
}
