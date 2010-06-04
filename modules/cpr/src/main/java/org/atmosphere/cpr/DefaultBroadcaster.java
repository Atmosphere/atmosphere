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
import org.atmosphere.util.LoggerUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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

    protected final Collection<AtmosphereResource<?,?>> events =
            new ConcurrentLinkedQueue<AtmosphereResource<?,?>>();
    protected BroadcasterConfig bc = AtmosphereServlet.getBroadcasterConfig();
    protected final BlockingQueue<Entry> messages =
            new LinkedBlockingQueue<Entry>();
    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected SCOPE scope = SCOPE.APPLICATION;
    protected String name = DefaultBroadcaster.class.getSimpleName();
    protected final ConcurrentLinkedQueue<Entry> delayedBroadcast =
            new ConcurrentLinkedQueue<Entry>();

    protected BroadcasterCache broadcasterCache;

    public DefaultBroadcaster() {
        this(DefaultBroadcaster.class.getSimpleName());
    }

    public DefaultBroadcaster(String name) {
        this.name = name;
        setID(name);
        broadcasterCache = new DefaultBroadcasterCache();
    }

    /**
     * {@inheritDoc}
     */
    public void destroy() {
        if (bc != null) {
            bc.destroy();
        }
        
        if (broadcasterCache != null){
            broadcasterCache.stop();
        }
        events.clear();
        messages.clear();
        delayedBroadcast.clear();
        broadcasterCache = null;
        started.set(false);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<AtmosphereResource<?,?>> getAtmosphereResources() {
        return Collections.unmodifiableCollection(events);
    }

    /**
     * {@inheritDoc}
     */
    public void setScope(SCOPE scope) {
        this.scope = scope;
        try {
            if (scope == SCOPE.REQUEST) {
                broadcasterCache = bc.getBroadcasterCache().getClass().newInstance();
            }
        } catch (Exception e) {
            LoggerUtils.getLogger().log(Level.SEVERE, "", e);
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
        for (AtmosphereResource<?,?> r : events) {
            r.resume();
        }
    }

    public class Entry {

        public Object message;
        public Object eventsToPush;
        public Future<?> future;

        public Entry(Object message, Object eventsToPush, Future future) {
            this.message = message;
            this.eventsToPush = eventsToPush;
            this.future = future;
        }
    }

    protected void start() {
        if (!started.getAndSet(true)) {

            if (bc == null) {
                LoggerUtils.getLogger().log(Level.WARNING, "BroadcasterConfig was null. It is recommended to use a BroadcasterFactory " +
                        "for creating Broadcaster instead of using new");
                bc = new BroadcasterConfig();
            }

            broadcasterCache = bc.getBroadcasterCache();
            broadcasterCache.start();

            bc.getExecutorService().submit(new Runnable() {

                public void run() {
                    Entry msg = null;
                    try {
                        msg = messages.take();
                        // Leader/follower
                        bc.getExecutorService().submit(this);
                        push(msg);
                    } catch (Throwable ex) {
                        // Catch all exception to avoid killing this thread.
                        LoggerUtils.getLogger().log(Level.SEVERE, null, ex);
                    } finally {
                        if (msg != null) {
                            if (msg.future instanceof BroadcasterFuture) {
                                ((BroadcasterFuture) msg.future).done();
                            } else {
                                msg.future.cancel(true);
                            }
                        }
                    }
                }
            });
        }
    }

    protected void push(Entry msg) {

        if (!delayedBroadcast.isEmpty()) {
            Iterator<Entry> i = delayedBroadcast.iterator();
            while (i.hasNext()) {
                Entry e = i.next();
                if (!(e.future instanceof BroadcasterFuture)) {
                    e.future.cancel(true);
                }
                try {
                    // Append so we do a single flush
                    if (e.message instanceof String
                            && msg.message instanceof String) {
                        msg.message = e.message.toString()
                                + msg.message.toString();
                    } else {
                        push(e);
                    }
                } finally {
                    i.remove();
                    if (e.future instanceof BroadcasterFuture) {
                        ((BroadcasterFuture) e.future).done();
                    }
                }
            }
        }

        if (events.isEmpty()) {
            trackBroadcastMessage(null, msg.message);
        }

        if (msg.eventsToPush == null) {
            for (AtmosphereResource<?,?> r : events) {
                push(r, msg.message);
            }
        } else if (msg.eventsToPush instanceof AtmosphereResource<?,?>) {
            push((AtmosphereResource<?,?>) msg.eventsToPush, msg.message);
        } else if (msg.eventsToPush instanceof Set) {
            Set<AtmosphereResource<?,?>> sub = (Set<AtmosphereResource<?,?>>) msg.eventsToPush;
            for (AtmosphereResource<?,?> r : sub) {
                push(r, msg.message);
            }
        }
    }

    protected void push(AtmosphereResource<?,?> r, Object msg) {
        AtmosphereResourceEvent e = null;
        synchronized (r) {
            if (!r.getAtmosphereResourceEvent().isSuspended())
                return;

            trackBroadcastMessage(r, msg);
            e = r.getAtmosphereResourceEvent();
            e.setMessage(msg);
            if (r instanceof AtmosphereEventLifecycle) {
                ((AtmosphereEventLifecycle) r).notifyListeners();
            }

            if (r.getAtmosphereResourceEvent() != null && !r.getAtmosphereResourceEvent().isCancelled()
                    && HttpServletRequest.class.isAssignableFrom(r.getRequest().getClass())) {
                HttpServletRequest.class.cast(r.getRequest()).setAttribute(CometSupport.MAX_INACTIVE, (Long)System.currentTimeMillis());
            }
            
            broadcast(r, e);
        }
    }

    protected void checkCachedAndPush(AtmosphereResource<?,?> r, AtmosphereResourceEvent e) {
        retrieveTrackedBroadcast(r, e);
        if (e.getMessage() instanceof List && !((List) e.getMessage()).isEmpty()) {
            broadcast(r, e);
        }
    }

    protected boolean retrieveTrackedBroadcast(final AtmosphereResource<?,?> r, final AtmosphereResourceEvent e) {
        List<Object> missedMsg = broadcasterCache.retrieveFromCache(r);
        if (!missedMsg.isEmpty()) {
            e.setMessage(missedMsg);
            return true;
        }
        return false;
    }

    protected void trackBroadcastMessage(final AtmosphereResource<?,?> r, Object msg) {
        broadcasterCache.addToCache(r, msg);
    }

    protected void broadcast(final AtmosphereResource<?,?> r, final AtmosphereResourceEvent e) {
        try {
            r.getAtmosphereConfig().getAtmosphereHandler(this).onStateChange(e);
        } catch (IOException ex) {
            onException(ex, r);
        } catch (RuntimeException ex) {
            onException(ex, r);
        }                                                                               
    }

    protected void onException(Throwable t, AtmosphereResource<?,?> r) {
        if (LoggerUtils.getLogger().isLoggable(Level.FINE)) {
            LoggerUtils.getLogger().log(Level.FINE, "", t);
        }
        if (t instanceof IOException && r instanceof AtmosphereEventLifecycle) {
            ((AtmosphereEventLifecycle) r).notifyListeners(new AtmosphereResourceEventImpl((AtmosphereResourceImpl) r, true, false));
            ((AtmosphereEventLifecycle) r).removeEventListeners();
        }
        removeAtmosphereResource(r);
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> broadcast(Object msg) {
        start();
        msg = filter(msg);
        if (msg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg);
        messages.offer(new Entry(msg, null, f));
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
    public Future<Object> broadcast(Object msg, AtmosphereResource<?,?> r) {
        start();
        msg = filter(msg);
        if (msg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg);
        messages.offer(new Entry(msg, r, f));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> broadcast(Object msg, Set<AtmosphereResource<?,?>> subset) {
        start();
        msg = filter(msg);
        if (msg == null) return null;

        BroadcasterFuture<Object> f = new BroadcasterFuture<Object>(msg);
        messages.offer(new Entry(msg, subset, f));
        return f;
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereResource<?,?> addAtmosphereResource(AtmosphereResource<?,?> r) {
        if (events.contains(r)) {
            return r;
        }

        // Re-add yourself
        if (events.isEmpty()) {
            BroadcasterFactory.getDefault().add(this, name);
        }

        events.add(r);
        checkCachedAndPush(r, r.getAtmosphereResourceEvent());
        return r;
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereResource<?,?> removeAtmosphereResource(AtmosphereResource r) {
        if (!events.contains(r)) {
            return null;
        }
        events.remove(r);

        // Will help preventing OOM.
        if (events.isEmpty()) {
            BroadcasterFactory.getDefault().remove(this, name);
        }
        return r;
    }

    /**
     * Set the {@link BroadcasterConfig} instance.
     *
     * @param bc
     */
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
    public Future<Object> delayBroadcast(Object o) {
        return delayBroadcast(o, 0, null);
    }

    /**
     * {@inheritDoc}
     */
    public Future<Object> delayBroadcast(final Object o, long delay, TimeUnit t) {
        final Object msg = filter(o);
        if (msg == null) return null;

        Future<Object> f = new BroadcasterFuture<Object>(msg);
        final Entry e = new Entry(msg, null, f);
        if (delay > 0) {
            f = bc.getScheduledExecutorService().schedule(new Callable<Object>() {

                public Object call() throws Exception {
                    delayedBroadcast.remove(e);
                    push(e);
                    return msg;
                }
            }, delay, t);
            e.future = f;
        }
        delayedBroadcast.offer(e);
        return f;
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
        if (period == 0 || t == null) {
            return null;
        }

        final Object msg = filter(o);
        if (msg == null) return null;

        final Entry e = new Entry(o, null, null);
        return bc.getScheduledExecutorService().scheduleWithFixedDelay(new Runnable() {
            public void run() {
                push(e);
            }
        }, waitFor, period, t);
    }

    public String toString(){
        return new StringBuilder(this.getClass().getName()).append("@").append(this.hashCode()).append("\n")
                .append("\tName: ").append(name).append("\n")
                .append("\tScope: ").append(scope).append("\n")
                .append("\tBroasdcasterCache ").append(broadcasterCache).append("\n")
                .append("\tAtmosphereResource: ").append(events.size()).append("\n")
                .toString();
    }
}
