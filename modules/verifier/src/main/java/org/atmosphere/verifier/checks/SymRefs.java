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
package org.atmosphere.verifier.checks;

import org.atmosphere.verifier.ast.SymRef;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Deep traversal of an argument value, surfacing every {@link SymRef} at
 * any nesting depth along with its AST path. A reference buried inside a
 * list or sub-map is reported exactly like a top-level one, so a tainted
 * or out-of-scope flow cannot hide behind a wrapper structure.
 *
 * <p>The {@code path} handed to the visitor extends the supplied root: a
 * list element appends {@code [i]} and a map entry appends {@code .key}.
 * A top-level reference keeps the root path unchanged, so existing
 * diagnostics (e.g. {@code "steps[1].arguments.body"}) are preserved.</p>
 */
final class SymRefs {

    private SymRefs() {
    }

    /** Visit every {@link SymRef} reachable from {@code value}. */
    static void forEach(Object value, String path, BiConsumer<SymRef, String> visitor) {
        if (value instanceof SymRef ref) {
            visitor.accept(ref, path);
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                forEach(entry.getValue(), path + "." + entry.getKey(), visitor);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                forEach(list.get(i), path + "[" + i + "]", visitor);
            }
        }
        // Literal leaf — nothing to surface.
    }
}
