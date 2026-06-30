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
package org.atmosphere.ai.resume;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared (de)serialization for the {@link EffectRecord.RunSeed} payload, so the
 * writer (the BuiltIn client recording the seed at dispatch) and the reader (the
 * resume engine reconstructing a crashed run) agree on one wire shape. The seed
 * is a plain record of {@link String}s, the chat history, and the tool digest —
 * Jackson maps it against the known {@code RunSeed} type, so the generic-Object
 * deserialization caveat that applies to the checkpoint store never applies here.
 *
 * @since 4.0
 */
public final class RunSeeds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunSeeds() {
        // static helper
    }

    /** Serialize a run seed to its journal payload string. */
    public static String serialize(EffectRecord.RunSeed seed) {
        return MAPPER.writeValueAsString(seed);
    }

    /** Parse a run seed from its journal payload string. */
    public static EffectRecord.RunSeed deserialize(String payload) {
        return MAPPER.readValue(payload, EffectRecord.RunSeed.class);
    }
}
