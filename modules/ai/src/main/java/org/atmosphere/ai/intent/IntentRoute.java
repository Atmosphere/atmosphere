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
package org.atmosphere.ai.intent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One destination an {@link IntentRouting} can send a request to. Its
 * {@link #name()} is an option of the {@code Question.Choice} the decision model
 * answers, and its {@link #description()} tells the model what the route is for.
 *
 * <ul>
 *   <li>{@link Handler} — a deterministic Java callback answers; no LLM call.</li>
 *   <li>{@link Llm} — the request continues down the normal dispatch path (the
 *       endpoint's or pipeline's runtime, including any {@code ModelRouter}).</li>
 *   <li>{@link Human} — the request is handed to a person. Every routing has
 *       exactly one; low-confidence and unanswered classifications land here.</li>
 * </ul>
 */
public sealed interface IntentRoute permits IntentRoute.Handler, IntentRoute.Llm, IntentRoute.Human {

    /** Route names: the same alphabet as a decision question id. */
    Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** The route name: an option key of the choice, and the {@code ai.intent.route} wire value. */
    String name();

    /** What the route is for, shown to the decision model; never {@code null}. */
    String description();

    /**
     * A deterministic handler answers the request.
     *
     * @param name        the route name
     * @param description what the route is for
     * @param handler     produces the reply
     */
    record Handler(String name, String description, IntentHandler handler) implements IntentRoute {
        public Handler {
            name = requireName(name);
            description = description == null ? "" : description;
            Objects.requireNonNull(handler, "handler");
        }
    }

    /**
     * The request continues to the LLM, exactly as it would without intent routing.
     *
     * @param name        the route name
     * @param description what the route is for
     */
    record Llm(String name, String description) implements IntentRoute {
        public Llm {
            name = requireName(name);
            description = description == null ? "" : description;
        }
    }

    /**
     * The request is escalated to a person. The handler hands the request to the
     * application's human queue (a ticket, a support inbox, an on-call page) and
     * returns the acknowledgement sent to the requester.
     *
     * @param name        the route name
     * @param description what the route is for
     * @param handler     hands the request off and produces the acknowledgement
     */
    record Human(String name, String description, IntentHandler handler) implements IntentRoute {
        public Human {
            name = requireName(name);
            description = description == null ? "" : description;
            Objects.requireNonNull(handler, "handler");
        }
    }

    /** A deterministic route. */
    static Handler handler(String name, String description, IntentHandler handler) {
        return new Handler(name, description, handler);
    }

    /** The normal LLM path. */
    static Llm llm(String name, String description) {
        return new Llm(name, description);
    }

    /** The human escalation route. */
    static Human human(String name, String description, IntentHandler handler) {
        return new Human(name, description, handler);
    }

    private static String requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("route name must match [A-Za-z0-9_-]{1,64}, got '"
                    + name + "'");
        }
        return name;
    }
}
