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
package org.atmosphere.ai.facts;

import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;

/**
 * Zero-dependency reference resolver. Supplies {@link FactKeys#TIME_NOW}
 * and {@link FactKeys#TIME_TIMEZONE} from the host clock and defaults
 * every other key to "unknown" (omitted from the bundle).
 *
 * <p>Production deployments override this via {@code ServiceLoader} with
 * a resolver that knows how to look up {@code user.*}, {@code featureflag.*},
 * and {@code audit.*} from their own identity / flag / audit backends.</p>
 */
public final class DefaultFactResolver implements FactResolver {

    private final Clock clock;
    private final ZoneId defaultZone;

    public DefaultFactResolver() {
        this(Clock.systemUTC(), ZoneId.of("UTC"));
    }

    public DefaultFactResolver(Clock clock, ZoneId defaultZone) {
        this.clock = clock;
        this.defaultZone = defaultZone;
    }

    @Override
    public FactBundle resolve(FactRequest request) {
        var facts = new LinkedHashMap<String, Object>();
        for (var key : request.keys()) {
            switch (key) {
                case FactKeys.TIME_NOW -> facts.put(key, clock.instant().toString());
                case FactKeys.TIME_TIMEZONE -> facts.put(key, defaultZone.getId());
                case FactKeys.USER_ID -> {
                    if (request.userId() != null) {
                        facts.put(key, request.userId());
                    }
                }
                default -> {
                    // unknown keys silently omitted so an upstream
                    // application-owned resolver can fill them in when
                    // chained via ServiceLoader priority.
                }
            }
        }
        return new FactBundle(facts);
    }
}
