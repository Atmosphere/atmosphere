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
package org.atmosphere.ai.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Read or write on an {@code EpisodicMemoryStore}. Emitted by both
 * {@code InMemoryEpisodicMemoryStore} and {@code JsonFileEpisodicMemoryStore}
 * so a single recording reveals memory hit-rate and write volume per
 * deployment.
 */
@Name("org.atmosphere.ai.EpisodicMemoryAccess")
@Label("Atmosphere AI Episodic Memory Access")
@Description("Read/write on an EpisodicMemoryStore")
@Category({"Atmosphere", "AI", "Memory"})
@StackTrace(false)
public final class EpisodicMemoryAccessEvent extends Event {

    public static final String OPERATION_STORE = "STORE";
    public static final String OPERATION_RECALL = "RECALL";
    public static final String OPERATION_FORGET = "FORGET";

    @Label("Store")
    @Description("Concrete EpisodicMemoryStore implementation class name")
    public String storeClass;

    @Label("Operation")
    @Description("STORE / RECALL / FORGET")
    public String operation;

    @Label("Type")
    @Description("Optional EpisodicMemoryType filter — empty when not applicable")
    public String type;

    @Label("Result Count")
    @Description("Entries written, returned, or removed by this access")
    public int count;
}
