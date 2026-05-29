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
package org.atmosphere.ai.code;

/**
 * A {@link CodeSandbox} that provisions its backing substrate lazily — on the
 * first {@link #exec(SandboxCommand)} call — and reuses it for the rest of the
 * session. This is what is registered into a session's injectables: enabling the
 * code-as-action feature must not spawn a container for every run, only for runs
 * where the model actually executes code.
 *
 * <p>{@link #close()} is idempotent and safe to call whether or not the backing
 * sandbox was ever created, so binding it to the session's terminal path never
 * fails (Correctness Invariant #2).</p>
 */
final class SessionSandbox implements CodeSandbox {

    private final CodeSandboxFactory factory;
    private final String sessionId;
    private final Object lock = new Object();

    private CodeSandbox delegate;
    private boolean closed;

    SessionSandbox(CodeSandboxFactory factory, String sessionId) {
        this.factory = factory;
        this.sessionId = sessionId;
    }

    @Override
    public String id() {
        synchronized (lock) {
            return delegate != null ? delegate.id() : ContainerCodeSandbox.containerName(sessionId);
        }
    }

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return !closed;
        }
    }

    @Override
    public SandboxResult exec(SandboxCommand command) throws SandboxException {
        return ensureProvisioned().exec(command);
    }

    @Override
    public void close() {
        CodeSandbox toClose;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = delegate;
            delegate = null;
        }
        if (toClose != null) {
            toClose.close();
        }
    }

    private CodeSandbox ensureProvisioned() throws SandboxException {
        synchronized (lock) {
            if (closed) {
                throw new SandboxException("Sandbox for session " + sessionId + " is closed");
            }
            if (delegate == null) {
                delegate = factory.create(sessionId);
            }
            return delegate;
        }
    }
}
