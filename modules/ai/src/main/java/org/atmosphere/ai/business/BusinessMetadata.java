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
package org.atmosphere.ai.business;

/**
 * Standard metadata keys for threading business-outcome correlates through
 * {@code AiRequest.metadata()} / {@code AgentExecutionContext.metadata()}.
 * Observability backends (Dynatrace, Datadog, New Relic, OpenTelemetry
 * exporters) read these keys off the active span attributes so a trace can
 * be correlated to revenue, customer id, or a session-cost budget — closing
 * the "AI → dollars" loop the 2026 Dynatrace agentic-AI report calls out.
 *
 * <p>The keys are deliberately a flat namespace under {@code business.*}
 * so they align with OpenTelemetry semantic-convention conventions for
 * attribute naming. The constants here are the canonical spelling; writing
 * them as string literals elsewhere is a discouraged anti-pattern because
 * a typo silently drops the attribute on the floor.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * session.stream(request.withMetadata(Map.of(
 *     BusinessMetadata.CUSTOMER_ID, "cust-42",
 *     BusinessMetadata.SESSION_REVENUE, 12.50,
 *     BusinessMetadata.EVENT_KIND, BusinessMetadata.EventKind.PURCHASE.wireName()
 * )));
 * }</pre>
 *
 * <p>Each request that carries one or more of these keys produces an
 * observability trace tagged with them. Pipelines that include an
 * {@code AiInterceptor} or {@code GatewayTraceExporter} propagate them
 * onto the outgoing span by default.</p>
 */
public final class BusinessMetadata {

    private BusinessMetadata() {
        // constants holder
    }

    /** Tenant / organization identifier — typically the billing-customer id. */
    public static final String TENANT_ID = "business.tenant.id";

    /** End-user identifier (distinct from tenant for multi-user tenants). */
    public static final String CUSTOMER_ID = "business.customer.id";

    /** Free-form user segment ("enterprise", "starter", "trial"). */
    public static final String CUSTOMER_SEGMENT = "business.customer.segment";

    /**
     * Pre-call budget for the session, in the currency implied by
     * {@link #SESSION_CURRENCY}. Observability exporters emit this as a
     * span attribute so cost dashboards can compute utilisation per user.
     */
    public static final String SESSION_REVENUE = "business.session.revenue";

    /**
     * Actual cost of the in-flight call, populated by the
     * {@code GatewayTraceExporter} after the LLM returns token usage.
     */
    public static final String SESSION_COST = "business.session.cost";

    /** Currency code (ISO 4217) for the revenue / cost figures. Default {@code USD}. */
    public static final String SESSION_CURRENCY = "business.session.currency";

    /** Business session identifier — typically correlates to the application's session store. */
    public static final String SESSION_ID = "business.session.id";

    /**
     * Kind of business event the call is part of. Use {@link EventKind#wireName()}
     * values as the string so downstream consumers can parse them back
     * into the enum.
     */
    public static final String EVENT_KIND = "business.event.kind";

    /** Optional downstream identifier — e.g. order id, ticket id, conversation id. */
    public static final String EVENT_SUBJECT = "business.event.subject";

    /**
     * Canonical event kinds. Applications supply the wire name via
     * {@link #wireName()} rather than the enum directly so the metadata
     * map stays JSON-serializable for off-process exporters.
     */
    public enum EventKind {
        NEW_CONVERSATION("new_conversation"),
        RETURNING_USER("returning_user"),
        PURCHASE("purchase"),
        SUPPORT_ESCALATION("support_escalation"),
        CHURN_RISK("churn_risk"),
        BILLING_ENQUIRY("billing_enquiry"),
        OTHER("other");

        private final String wire;

        EventKind(String wire) {
            this.wire = wire;
        }

        public String wireName() {
            return wire;
        }

        /** Parse a wire name back, returning {@link #OTHER} on unknown input. */
        public static EventKind fromWire(String wire) {
            if (wire == null) {
                return OTHER;
            }
            for (var k : values()) {
                if (k.wire.equals(wire)) {
                    return k;
                }
            }
            return OTHER;
        }
    }
}
