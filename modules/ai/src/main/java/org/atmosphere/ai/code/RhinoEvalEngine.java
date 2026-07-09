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
 * The default {@link EvalEngine}: a sandboxed in-process JavaScript evaluator
 * backed by Mozilla Rhino. Registered via {@code META-INF/services/}
 * {@code org.atmosphere.ai.code.EvalEngine} at priority {@code 0}, so any
 * explicitly-added engine (GraalJS, a Python interpreter, …) can take precedence.
 *
 * <p>Rhino is an <em>optional</em> dependency. This adapter carries no Rhino
 * types in its own signature — the Rhino-touching {@link RhinoScriptSandbox} is
 * referenced only inside method bodies — so the class loads even when Rhino is
 * absent, in which case {@link #isAvailable()} reports {@code false} and the
 * engine is skipped during {@link java.util.ServiceLoader} resolution.</p>
 */
public final class RhinoEvalEngine implements EvalEngine {

    private volatile RhinoScriptSandbox sandbox;
    private volatile EvalLimits sandboxLimits;

    @Override
    public String language() {
        return "javascript";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.mozilla.javascript.Context");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public EvalResult evaluate(String code, EvalLimits limits) {
        return sandbox(limits).evaluate(code);
    }

    /** Lazily build (and cache) a Rhino sandbox for the given limits. */
    private RhinoScriptSandbox sandbox(EvalLimits limits) {
        var current = sandbox;
        if (current != null && limits.equals(sandboxLimits)) {
            return current;
        }
        synchronized (this) {
            if (sandbox == null || !limits.equals(sandboxLimits)) {
                sandbox = new RhinoScriptSandbox(limits.instructionBudget(),
                        limits.timeoutMillis(), limits.maxOutputChars());
                sandboxLimits = limits;
            }
            return sandbox;
        }
    }
}
