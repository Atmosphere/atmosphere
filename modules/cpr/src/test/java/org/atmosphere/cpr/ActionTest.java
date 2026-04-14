/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionTest {

    @Test
    void defaultConstructorCreatesCreatedType() {
        var action = new Action();
        assertEquals(Action.TYPE.CREATED, action.type());
        assertEquals(-1L, action.timeout());
    }

    @Test
    void singleArgConstructorSetsDefaultTimeout() {
        var action = new Action(Action.TYPE.SUSPEND);
        assertEquals(Action.TYPE.SUSPEND, action.type());
        assertEquals(-1L, action.timeout());
    }

    @Test
    void fullConstructorRetainsBothFields() {
        var action = new Action(Action.TYPE.TIMEOUT, 5000L);
        assertEquals(Action.TYPE.TIMEOUT, action.type());
        assertEquals(5000L, action.timeout());
    }

    @Test
    void staticCancelledConstant() {
        assertEquals(Action.TYPE.CANCELLED, Action.CANCELLED.type());
    }

    @Test
    void staticContinueConstant() {
        assertEquals(Action.TYPE.CONTINUE, Action.CONTINUE.type());
    }

    @Test
    void staticCreatedConstant() {
        assertEquals(Action.TYPE.CREATED, Action.CREATED.type());
    }

    @Test
    void staticResumeConstant() {
        assertEquals(Action.TYPE.RESUME, Action.RESUME.type());
    }

    @Test
    void staticSuspendConstant() {
        assertEquals(Action.TYPE.SUSPEND, Action.SUSPEND.type());
    }

    @Test
    void staticDestroyedConstant() {
        assertEquals(Action.TYPE.DESTROYED, Action.DESTROYED.type());
    }

    @Test
    void staticSkipAtmosphereHandlerConstant() {
        assertEquals(Action.TYPE.SKIP_ATMOSPHEREHANDLER, Action.SKIP_ATMOSPHEREHANDLER.type());
    }

    @Test
    void allTypeEnumValues() {
        assertEquals(9, Action.TYPE.values().length);
    }

    @Test
    void suspendMessageType() {
        assertEquals(Action.TYPE.SUSPEND_MESSAGE, Action.TYPE.valueOf("SUSPEND_MESSAGE"));
    }
}
