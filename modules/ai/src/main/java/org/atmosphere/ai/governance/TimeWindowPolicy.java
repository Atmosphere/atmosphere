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
package org.atmosphere.ai.governance;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Admit only when the current wall-clock time falls within a configured
 * day-of-week + time-of-day window. Denies outside the window.
 *
 * <h2>Use cases</h2>
 * <ul>
 *   <li>Business-hours-only endpoints (AI agent runs 9am–5pm Mon–Fri)</li>
 *   <li>Maintenance freeze windows (no AI traffic during weekly deploy)</li>
 *   <li>Compliance posture: regulated decisions require human oversight
 *       outside defined hours</li>
 * </ul>
 *
 * <p>The policy holds a {@link Clock} (defaulting to the system UTC clock)
 * so tests can drive time deterministically. The {@link ZoneId} is
 * operator-chosen; a policy configured for {@code America/New_York}
 * 09:00–17:00 behaves correctly across DST transitions.</p>
 *
 * <p>Window semantics: {@code start} is inclusive, {@code end} is
 * exclusive. A window spanning midnight (e.g. 22:00–06:00) is supported —
 * the implementation detects the wrap-around and admits on either side.</p>
 *
 * <p>Post-response phase always admits — time-of-day gating is an
 * admission concern, not a response one.</p>
 */
public final class TimeWindowPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final LocalTime start;
    private final LocalTime end;
    private final Set<DayOfWeek> days;
    private final ZoneId zone;
    private final Clock clock;

    public TimeWindowPolicy(String name, LocalTime start, LocalTime end,
                            Set<DayOfWeek> days, ZoneId zone) {
        this(name, "code:" + TimeWindowPolicy.class.getName(), "1",
                start, end, days, zone, Clock.systemUTC());
    }

    public TimeWindowPolicy(String name, String source, String version,
                            LocalTime start, LocalTime end,
                            Set<DayOfWeek> days, ZoneId zone, Clock clock) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("start and end must differ");
        }
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("days must be non-empty");
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.start = start;
        this.end = end;
        this.days = EnumSet.copyOf(days);
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Factory — common "business hours" pattern: Mon–Fri, 9am–5pm. */
    public static TimeWindowPolicy businessHours(String name, ZoneId zone) {
        return new TimeWindowPolicy(name, LocalTime.of(9, 0), LocalTime.of(17, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                zone);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public LocalTime start() { return start; }
    public LocalTime end() { return end; }
    public Set<DayOfWeek> days() { return EnumSet.copyOf(days); }
    public ZoneId zone() { return zone; }

    /** True when {@code now} (computed via the installed clock) is inside the window. */
    public boolean isInsideWindow() {
        var now = ZonedDateTime.now(clock.withZone(zone));
        return isInsideWindow(now);
    }

    /** Exposed for tests — accepts an explicit point in time. */
    boolean isInsideWindow(ZonedDateTime now) {
        if (!days.contains(now.getDayOfWeek())) return false;
        var t = now.toLocalTime();
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        // Wrap-around window (e.g. 22:00–06:00): inside if on/after start OR before end.
        return !t.isBefore(start) || t.isBefore(end);
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        if (isInsideWindow()) {
            return PolicyDecision.admit();
        }
        return PolicyDecision.deny(
                "outside allowed window " + start + "-" + end + " (" + zone + ") on "
                        + days);
    }
}
