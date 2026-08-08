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
package org.atmosphere.ai.state.seal;

/**
 * Fail-closed refusal from the agent-state sealing control: a state file
 * failed integrity verification, lacked a seal under strict mode, could not
 * be read for verification, or a seal could not be written after a save.
 *
 * <p>Extends {@link IllegalStateException} to match the checkpoint cipher's
 * fail-closed convention while remaining catchable specifically. The message
 * always names the remediation (the {@link AgentStateReseal} step) when the
 * refusal can be resolved by an operator blessing a deliberate edit.</p>
 */
public class AgentStateSealException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AgentStateSealException(String message) {
        super(message);
    }

    public AgentStateSealException(String message, Throwable cause) {
        super(message, cause);
    }
}
