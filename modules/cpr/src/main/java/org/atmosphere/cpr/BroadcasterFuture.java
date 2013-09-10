/*
 * Copyright 2013 Jeanfrancois Arcand
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simple {@link Future} that can be used when awaiting for a {@link Broadcaster} to finish
 * it's broadcast operations to {@link AtmosphereHandler}
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterFuture<E> implements Future {

    private final CountDownLatch latch;
    private boolean isCancelled = false;
    private boolean isDone = false;
    private final E msg;
    private final Future<?> innerFuture;

    public BroadcasterFuture(E msg) {
        this(null, msg);
    }

    public BroadcasterFuture(Future<?> innerFuture, E msg) {
        this(innerFuture, msg, 1);
    }

    public BroadcasterFuture(E msg, int latchCount) {
        this(null, msg, latchCount);
    }

    public BroadcasterFuture(Future<?> innerFuture, E msg, int latchCount) {
        this.msg = msg;
        this.innerFuture = innerFuture;
        if (innerFuture == null) {
            latch = new CountDownLatch(latchCount);
        } else {
            latch = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel(boolean b) {

        if (innerFuture != null) {
            return innerFuture.cancel(b);
        }
        isCancelled = true;

        while (latch.getCount() > 0) {
            latch.countDown();
        }
        return isCancelled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {

        if (innerFuture != null) {
            return innerFuture.isCancelled();
        }

        return isCancelled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {

        if (innerFuture != null) {
            return innerFuture.isDone();
        }

        isDone = true;
        return isDone;
    }

    /**
     * Invoked when a {@link Broadcaster} completed it broadcast operation.
     */
    public BroadcasterFuture<E> done() {
        isDone = true;

        if (latch != null) {
            latch.countDown();
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public E get() throws InterruptedException, ExecutionException {
        if (innerFuture != null) {
            return (E) innerFuture.get();
        }

        latch.await();
        return msg;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public E get(long l, TimeUnit tu) throws InterruptedException, ExecutionException, TimeoutException {

        if (innerFuture != null) {
            return (E) innerFuture.get();
        }

        latch.await(l, tu);
        return msg;
    }
}
