// ========================================================================
// Copyright 2006 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.atmosphere.plugin.bayeux;

import org.atmosphere.cpr.AtmosphereResource;
import org.cometd.server.ClientImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

import javax.servlet.http.HttpServletResponse;

/* ------------------------------------------------------------ */

/**
 * Extension of {@link ClientImpl} that uses {@link Continuation}s to resume
 * clients waiting for messages. Continuation clients are used for remote
 * clients and are removed if they are not accessed within an idle timeout.
 *
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class AtmosphereBayeuxClient extends ClientImpl {
    private final AtmosphereContinuationBayeux _bayeux;
    private final Timeout.Task _intervalTimeoutTask;
    private final Timeout.Task _lazyTimeoutTask;
    private long _accessed;
    private volatile AtmosphereResource _continuation;
    private volatile boolean _lazyResuming;

    /* ------------------------------------------------------------ */

    protected AtmosphereBayeuxClient(AtmosphereContinuationBayeux bayeux) {
        super(bayeux);
        _bayeux = bayeux;

        if (isLocal()) {
            _intervalTimeoutTask = null;
            _lazyTimeoutTask = null;
        } else {
            // The timeout task for when a long poll does not arrive.
            _intervalTimeoutTask = new Timeout.Task() {
                @Override
                public void expired() {
                    remove(true);
                }

                @Override
                public String toString() {
                    return "T-" + AtmosphereBayeuxClient.this.toString();
                }
            };

            // The timeout task for lazy messages
            _lazyTimeoutTask = new Timeout.Task() {
                @Override
                public void expired() {
                    _lazyResuming = false;
                    if (hasMessages())
                        resume();
                }

                @Override
                public String toString() {
                    return "L-" + AtmosphereBayeuxClient.this.toString();
                }
            };

            _bayeux.startTimeout(_intervalTimeoutTask, _bayeux.getMaxInterval());
        }
    }

    /* ------------------------------------------------------------ */

    public void setContinuation(AtmosphereResource continuation) {
        if (continuation == null) {
            synchronized (this) {
                // This is the end of a long poll
                _continuation = null;

                // Set timeout when to expect the next long poll
                if (_intervalTimeoutTask != null)
                    _bayeux.startTimeout(_intervalTimeoutTask, _bayeux.getMaxInterval());
            }
        } else {
            synchronized (this) {
                AtmosphereResource oldContinuation = _continuation;
                _continuation = continuation;

                _bayeux.cancelTimeout(_intervalTimeoutTask);
                _accessed = _bayeux.getNow();

                if (oldContinuation == null) {
                    // This is the start of a long poll
                } else {
                    // This is the reload case: there is an outstanding connect,
                    // and the client issues a new connect.
                    // We return the old connect via complete() since we do not
                    // want to resume() otherwise the old connect will be
                    // redispatched and will overwrite the new connect.
                    try {
                        if (oldContinuation.getAtmosphereResourceEvent().isSuspended()) {
                            ((HttpServletResponse) oldContinuation.getResponse()).sendError(HttpServletResponse.SC_REQUEST_TIMEOUT);
                            oldContinuation.resume();
                        }
                    }
                    catch (Exception e) {
                        Log.debug(e);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */

    public AtmosphereResource getContinuation() {
        return _continuation;
    }

    /* ------------------------------------------------------------ */

    @Override
    public void lazyResume() {
        int max = _bayeux.getMaxLazyLatency();
        if (max > 0 && _lazyTimeoutTask != null && !_lazyResuming) {
            _lazyResuming = true;
            // use modulo so all lazy clients do not wakeup at once
            _bayeux.startTimeout(_lazyTimeoutTask, _accessed % max);
        }
    }

    /* ------------------------------------------------------------ */

    @Override
    public void resume() {
        synchronized (this) {
            if (_continuation != null) {
                _continuation.resume();
            }
            _continuation = null;
        }
    }

    /* ------------------------------------------------------------ */

    @Override
    public boolean isLocal() {
        return false;
    }

    /* ------------------------------------------------------------ */

    public void access() {
        synchronized (this) {
            _accessed = _bayeux.getNow();
            if (_intervalTimeoutTask != null && _intervalTimeoutTask.isScheduled()) {
                // reschedule the timer even though it may be cancelled next...
                // it might not be.
                _intervalTimeoutTask.reschedule();
            }
        }
    }

    /* ------------------------------------------------------------ */

    public synchronized long lastAccessed() {
        return _accessed;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see org.cometd.server.ClientImpl#remove(boolean)
     */

    @Override
    public void remove(boolean wasTimeout) {
        synchronized (this) {
            if (!wasTimeout && _intervalTimeoutTask != null)
                _bayeux.cancelTimeout(_intervalTimeoutTask);
        }
        super.remove(wasTimeout);
    }
}
