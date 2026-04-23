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
package org.atmosphere.coordinator.commitment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the flag-off-default contract. The property defaults to
 * {@code false}; operators who explicitly opt in accept the
 * {@code @Experimental} schema and its 2026-Q4 migration horizon.
 */
class CommitmentRecordsFlagTest {

    private String savedProperty;

    @BeforeEach
    void setUp() {
        savedProperty = System.getProperty(CommitmentRecordsFlag.PROPERTY_NAME);
        System.clearProperty(CommitmentRecordsFlag.PROPERTY_NAME);
        CommitmentRecordsFlag.override(null);
    }

    @AfterEach
    void tearDown() {
        CommitmentRecordsFlag.override(null);
        if (savedProperty == null) {
            System.clearProperty(CommitmentRecordsFlag.PROPERTY_NAME);
        } else {
            System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, savedProperty);
        }
    }

    @Test
    void defaultIsDisabledWhenNoPropertyOrOverride() {
        assertFalse(CommitmentRecordsFlag.isEnabled(),
                "contract: flag-off default");
    }

    @Test
    void systemPropertyTrueEnables() {
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "true");
        assertTrue(CommitmentRecordsFlag.isEnabled());
    }

    @Test
    void systemPropertyCaseInsensitive() {
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "TRUE");
        assertTrue(CommitmentRecordsFlag.isEnabled());
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "True");
        assertTrue(CommitmentRecordsFlag.isEnabled());
    }

    @Test
    void systemPropertyAnyOtherValueKeepsFlagOff() {
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "false");
        assertFalse(CommitmentRecordsFlag.isEnabled());

        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "yes");
        assertFalse(CommitmentRecordsFlag.isEnabled(),
                "only literal 'true' opts in — conservative defaults");
    }

    @Test
    void overrideTakesPrecedenceOverSystemProperty() {
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "false");
        CommitmentRecordsFlag.override(Boolean.TRUE);
        assertTrue(CommitmentRecordsFlag.isEnabled(),
                "explicit test override beats the property");

        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "true");
        CommitmentRecordsFlag.override(Boolean.FALSE);
        assertFalse(CommitmentRecordsFlag.isEnabled(),
                "override can force off even when property enables");
    }

    @Test
    void nullOverrideFallsBackToSystemProperty() {
        System.setProperty(CommitmentRecordsFlag.PROPERTY_NAME, "true");
        CommitmentRecordsFlag.override(Boolean.FALSE);
        assertFalse(CommitmentRecordsFlag.isEnabled());

        CommitmentRecordsFlag.override(null);
        assertTrue(CommitmentRecordsFlag.isEnabled(),
                "clearing the override restores system-property semantics");
    }

    @Test
    void propertyNameIsTheDocumentedConstant() {
        // Property name is a public contract — docs + ops runbooks reference
        // `atmosphere.ai.governance.commitment-records.enabled` verbatim.
        // Renaming this constant without bumping the docs breaks operators.
        assertTrue(CommitmentRecordsFlag.PROPERTY_NAME
                        .equals("atmosphere.ai.governance.commitment-records.enabled"),
                "property name must match the documented constant verbatim");
    }
}
