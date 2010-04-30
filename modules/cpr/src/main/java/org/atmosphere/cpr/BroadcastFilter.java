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
 */

package org.atmosphere.cpr;

/**
 * Transform a message of type 'E" before it get broadcasted to
 * {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent) }
 * <p/>
 * See {@link org.atmosphere.util.XSSHtmlFilter} for an example.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcastFilter {

    /**
     * Simple class that tells the {@link Broadcaster} to broacast or not
     * the transformed value.
     */
    public class BroadcastAction {

        private final ACTION a;
        private final Object o;

        public enum ACTION {
            CONTINUE, ABORT
        }

        public BroadcastAction(ACTION a, Object o) {
            this.a = a;
            this.o = o;
        }

        public BroadcastAction(Object o) {
            this.a = ACTION.CONTINUE;
            this.o = o;
        }

        public Object message() {
            return o;
        }

        public ACTION action() {
            return a;
        }

    }

    /**
     * Transform or Filter a message. Return null to tell the associated
     * {@link Broadcaster} to discard the message, e.g to not broadcast it.
     *
     * @param message Object a message
     * @return a transformed message.
     */
    BroadcastAction filter(Object message);
}
