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
package org.gwtcomet.server;

import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StreamingServiceBusiness {

    private final static long serialVersionUID = 1; // My internal version
    private final ConcurrentMap<String, ConcurrentHashMap<String, Boolean>> queues = new ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>();
    private final Observable observable = new EventObservable();

    public StreamingServiceBusiness() {
        super();
    }

    public static class EventObservable extends Observable {

        public synchronized void notifyObservers(Object arg) {
            setChanged();
            super.notifyObservers(arg);
            notifyAll();
        }
    }

    public void subscribeToTopic(String topicName, String clientName) {
        if (!queues.containsKey(topicName)) {
            queues.put(topicName, new ConcurrentHashMap<String, Boolean>());
        }

        queues.get(topicName).putIfAbsent(clientName, Boolean.TRUE);
    }

    public void unsubscribeFromTopic(String topicName, String clientName) {
        if (queues.containsKey(topicName) && queues.get(topicName).containsKey(clientName)) {
            queues.get(topicName).remove(clientName);
        }
    }

    public boolean isClientSubscribedToQueue(String topicName, String clientName) {
        return queues.containsKey(topicName) && queues.get(topicName).containsKey(clientName);
    }

    public static class Event {

        public final String queueName;
        public final String message;
        public final long eventTime = System.currentTimeMillis();

        public Event(String queueName, String message) {
            this.queueName = queueName;
            this.message = message;
        }

        public String toString() {
            return "Event(" + queueName + "," + message + "," + eventTime + ")";
        }
    }

    public void addObserver(Observer observer) {
        observable.addObserver(observer);
    }

    public void deleteObserver(Observer observer) {
        observable.deleteObserver(observer);
    }

    public void waitForEvents(long keepAliveTimeout) {
        try {
            synchronized (observable) {
                observable.wait(keepAliveTimeout);
            }
        } catch (InterruptedException e) {
        }
    }

    public void sendMessage(String queueName, String message) {

        synchronized (observable) {
            observable.notifyObservers(new Event(queueName, message));
        }
    }
}
