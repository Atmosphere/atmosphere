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

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWindowPolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static PolicyContext preAdm() {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("msg", null, null, null, null, null, null, null, null),
                "");
    }

    private static Clock clockAt(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), UTC);
    }

    @Test
    void admitsWhenInsideStandardBusinessHours() {
        var policy = new TimeWindowPolicy("biz", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                UTC,
                // 2026-04-23 is a Thursday
                clockAt("2026-04-23T10:30:00Z"));

        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(preAdm()));
    }

    @Test
    void deniesWhenOutsideBusinessHoursByTime() {
        var policy = new TimeWindowPolicy("biz", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                EnumSet.allOf(DayOfWeek.class),
                UTC,
                clockAt("2026-04-23T23:00:00Z"));

        var deny = assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(preAdm()));
        assertTrue(deny.reason().contains("outside allowed window"));
    }

    @Test
    void deniesOnUnselectedDaysOfWeek() {
        var policy = new TimeWindowPolicy("mon-only", "code:test", "1",
                LocalTime.of(0, 0), LocalTime.of(23, 59),
                EnumSet.of(DayOfWeek.MONDAY),
                UTC,
                clockAt("2026-04-23T10:00:00Z")); // Thursday

        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(preAdm()));
    }

    @Test
    void startInclusiveEndExclusive() {
        var days = EnumSet.allOf(DayOfWeek.class);

        // At 09:00 sharp — inside
        var atStart = new TimeWindowPolicy("bh", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0), days, UTC,
                clockAt("2026-04-23T09:00:00Z"));
        assertInstanceOf(PolicyDecision.Admit.class, atStart.evaluate(preAdm()));

        // At 17:00 sharp — outside (end is exclusive)
        var atEnd = new TimeWindowPolicy("bh", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0), days, UTC,
                clockAt("2026-04-23T17:00:00Z"));
        assertInstanceOf(PolicyDecision.Deny.class, atEnd.evaluate(preAdm()));
    }

    @Test
    void wrapAroundMidnightWindowAdmitsOnEitherSide() {
        var days = EnumSet.allOf(DayOfWeek.class);

        // Window 22:00–06:00 UTC — at 23:00, should admit
        var lateNight = new TimeWindowPolicy("night-shift", "code:test", "1",
                LocalTime.of(22, 0), LocalTime.of(6, 0), days, UTC,
                clockAt("2026-04-23T23:00:00Z"));
        assertInstanceOf(PolicyDecision.Admit.class, lateNight.evaluate(preAdm()));

        // At 03:00 — should also admit
        var earlyMorning = new TimeWindowPolicy("night-shift", "code:test", "1",
                LocalTime.of(22, 0), LocalTime.of(6, 0), days, UTC,
                clockAt("2026-04-23T03:00:00Z"));
        assertInstanceOf(PolicyDecision.Admit.class, earlyMorning.evaluate(preAdm()));

        // At 12:00 midday — should deny
        var midday = new TimeWindowPolicy("night-shift", "code:test", "1",
                LocalTime.of(22, 0), LocalTime.of(6, 0), days, UTC,
                clockAt("2026-04-23T12:00:00Z"));
        assertInstanceOf(PolicyDecision.Deny.class, midday.evaluate(preAdm()));
    }

    @Test
    void zoneIsHonoredForDayOfWeekAndTimeOfDay() {
        var days = EnumSet.of(DayOfWeek.FRIDAY);
        // 2026-04-23T23:30:00Z is Thursday evening UTC.
        // In America/New_York (UTC-4 during DST) it's 19:30 Thursday — still
        // Thursday, so a Friday-only window must deny.
        var nyFriday = new TimeWindowPolicy("ny-friday", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                days,
                ZoneId.of("America/New_York"),
                clockAt("2026-04-23T23:30:00Z"));
        assertInstanceOf(PolicyDecision.Deny.class, nyFriday.evaluate(preAdm()));

        // 2026-04-24T13:00:00Z is Friday morning UTC = 09:00 NY — inside window.
        var nyFridayMorning = new TimeWindowPolicy("ny-friday", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                days,
                ZoneId.of("America/New_York"),
                clockAt("2026-04-24T13:00:00Z"));
        assertInstanceOf(PolicyDecision.Admit.class, nyFridayMorning.evaluate(preAdm()));
    }

    @Test
    void postResponseAlwaysAdmits() {
        var policy = new TimeWindowPolicy("biz", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                EnumSet.of(DayOfWeek.MONDAY), UTC,
                clockAt("2026-04-23T23:00:00Z"));
        var post = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("m", null, null, null, null, null, null, null, null),
                "response");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(post));
    }

    @Test
    void isInsideWindowExposedForAdminIntrospection() {
        var days = EnumSet.allOf(DayOfWeek.class);
        var policy = new TimeWindowPolicy("bh", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0), days, UTC,
                clockAt("2026-04-23T10:30:00Z"));
        assertTrue(policy.isInsideWindow());

        var outside = new TimeWindowPolicy("bh", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0), days, UTC,
                clockAt("2026-04-23T23:00:00Z"));
        assertFalse(outside.isInsideWindow());
    }

    @Test
    void businessHoursFactoryMatchesCommonPattern() {
        var policy = TimeWindowPolicy.businessHours("bh", UTC);
        assertEquals(LocalTime.of(9, 0), policy.start());
        assertEquals(LocalTime.of(17, 0), policy.end());
        assertEquals(5, policy.days().size());
        assertTrue(policy.days().contains(DayOfWeek.MONDAY));
        assertFalse(policy.days().contains(DayOfWeek.SATURDAY));
    }

    @Test
    void rejectsEqualStartAndEnd() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWindowPolicy("bad", LocalTime.of(9, 0), LocalTime.of(9, 0),
                        EnumSet.of(DayOfWeek.MONDAY), UTC));
    }

    @Test
    void rejectsEmptyDaySet() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWindowPolicy("bad", LocalTime.of(9, 0), LocalTime.of(17, 0),
                        EnumSet.noneOf(DayOfWeek.class), UTC));
    }

    @Test
    void helperIsInsideWindowHandlesExplicitZonedDateTime() {
        var days = EnumSet.allOf(DayOfWeek.class);
        var policy = new TimeWindowPolicy("bh", "code:test", "1",
                LocalTime.of(9, 0), LocalTime.of(17, 0), days, UTC, Clock.systemUTC());

        assertTrue(policy.isInsideWindow(
                ZonedDateTime.parse("2026-04-23T10:30:00Z")));
        assertFalse(policy.isInsideWindow(
                ZonedDateTime.parse("2026-04-23T22:30:00Z")));
    }
}
