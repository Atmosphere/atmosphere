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
//========================================================================

package org.atmosphere.plugin.bayeux;

import org.cometd.server.AbstractBayeux;
import org.cometd.server.ClientImpl;
import org.eclipse.jetty.util.thread.Timeout;

import javax.servlet.ServletContext;
import java.util.Timer;
import java.util.TimerTask;

/* ------------------------------------------------------------ */

/**
 * Extension of Bayeux that uses {@link AtmosphereBayeuxClient}s.
 *
 * @author gregw
 */
public class AtmosphereContinuationBayeux extends AbstractBayeux {

    private transient Timer _tick;
    private transient Timeout _timeout;
    private long _now;
    private int __id = 0;

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see org.cometd.server.AbstractBayeux#newClient(java.lang.String,
     * dojox.io.cometd.Destination)
     */

    @Override
    public ClientImpl newRemoteClient() {
        ClientImpl client = new AtmosphereBayeuxClient(this);
        addClient(client, null);
        return client;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see
     * org.cometd.server.AbstractBayeux#initialize(javax.servlet.ServletContext)
     */

    @Override
    protected void initialize(ServletContext context) {
        super.initialize(context);

        _tick = new Timer("AtmosphereContinuationBayeux-" + __id++, true);
        _timeout = new Timeout();

        _tick.schedule(new TimerTask() {
            @Override
            public void run() {
                _now = System.currentTimeMillis();
                _timeout.tick(_now);
            }
        }, 100L, 100L);
    }

    /* ------------------------------------------------------------ */

    long getNow() {
        return _now;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see
     * org.cometd.server.AbstractBayeux#initialize(javax.servlet.ServletContext)
     */

    public void destroy() {
        _tick.cancel();
    }

    /* ------------------------------------------------------------ */

    void startTimeout(Timeout.Task timeout, long interval) {
        synchronized (_timeout) {
            _timeout.schedule(timeout, interval);
        }
    }

    /* ------------------------------------------------------------ */

    void cancelTimeout(Timeout.Task timeout) {
        synchronized (_timeout) {
            if (timeout != null)
                timeout.cancel();
        }
    }

}
