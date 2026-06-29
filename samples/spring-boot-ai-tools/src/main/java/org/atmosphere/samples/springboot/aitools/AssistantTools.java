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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.RequiresApproval;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Framework-agnostic tool provider using Atmosphere's {@code @AiTool} annotation.
 *
 * <p>These tools are registered in the global {@code ToolRegistry} at startup
 * and automatically bridged to whichever AI backend is active:</p>
 * <ul>
 *   <li><b>Spring AI</b> → {@code ToolCallback} via {@code SpringAiToolBridge}</li>
 *   <li><b>LangChain4j</b> → {@code ToolSpecification} via {@code LangChain4jToolBridge}</li>
 *   <li><b>Google ADK</b> → {@code BaseTool} via {@code AdkToolBridge}</li>
 * </ul>
 *
 * <p>The key advantage: switch your AI backend without rewriting tool code.</p>
 */
public class AssistantTools {

    /**
     * Illustrative order book: order id → order total in cents. A refund posts
     * the matching total back to the customer. Unknown orders fall back to a
     * flat amount so the demo never throws on an arbitrary id.
     */
    private static final Map<String, Long> ORDER_TOTALS_CENTS = Map.of(
            "A-1001", 4999L,
            "A-1002", 12900L,
            "A-1003", 2500L);

    private static final long DEFAULT_REFUND_CENTS = 1000L;

    /**
     * In-memory refund ledger: order id → cumulative cents refunded. This is
     * the observable side effect of the money-moving {@code issue_refund} tool.
     * A refund only lands here <em>after</em> a human approves the
     * {@code @RequiresApproval} gate — the approval gate in
     * {@code ToolExecutionHelper} short-circuits denied/timed-out/un-wired
     * approvals before the executor (and therefore this mutation) ever runs.
     */
    private final Map<String, Long> refundLedgerCents = new ConcurrentHashMap<>();

    @AiTool(name = "get_current_time",
            description = "Returns the current date and time in the server's timezone")
    public String getCurrentTime() {
        return ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }

    @AiTool(name = "get_city_time",
            description = "Returns the current time in a specific city")
    public String getCityTime(
            @Param(value = "city", description = "City name (e.g., Tokyo, London, Paris, New York, Sydney)")
            String city) {
        var zone = switch (city.toLowerCase()) {
            case "tokyo" -> "Asia/Tokyo";
            case "london" -> "Europe/London";
            case "paris" -> "Europe/Paris";
            case "sydney" -> "Australia/Sydney";
            case "new york", "nyc" -> "America/New_York";
            case "los angeles", "la" -> "America/Los_Angeles";
            case "berlin" -> "Europe/Berlin";
            case "mumbai" -> "Asia/Kolkata";
            case "beijing" -> "Asia/Shanghai";
            default -> "UTC";
        };
        return city + ": " + ZonedDateTime.now(ZoneId.of(zone))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss (z)"));
    }

    @AiTool(name = "get_weather",
            description = "Returns a weather report for a city with temperature and conditions")
    public String getWeather(
            @Param(value = "city", description = "City name to get weather for")
            String city) {
        // Illustrative canned data: this sample demonstrates @AiTool tool-calling
        // wiring, not a live weather service. Replace the body with a real
        // weather API call to make it production-grade.
        return switch (city.toLowerCase()) {
            case "london" -> "London: Cloudy, 15°C / 59°F, 80% humidity";
            case "paris" -> "Paris: Partly cloudy, 20°C / 68°F, 65% humidity";
            case "tokyo" -> "Tokyo: Rainy, 22°C / 72°F, 90% humidity";
            case "sydney" -> "Sydney: Clear, 28°C / 82°F, 45% humidity";
            case "new york", "nyc" -> "New York: Sunny, 25°C / 77°F, 55% humidity";
            case "los angeles", "la" -> "Los Angeles: Sunny, 30°C / 86°F, 30% humidity";
            default -> city + ": Clear, 22°C / 72°F, 50% humidity";
        };
    }

    @AiTool(name = "reset_city_data",
            description = "Reset all cached weather and time data for a city. This is a destructive operation.")
    @RequiresApproval("This will reset all cached data for the city. Are you sure?")
    public String resetCityData(
            @Param(value = "city", description = "City name to reset data for")
            String city) {
        return "All cached data for " + city + " has been reset successfully.";
    }

    /**
     * Money-moving tool: posts a refund for an order. Because it moves money it
     * is gated by {@code @RequiresApproval} — the pipeline pauses, asks a human
     * to approve, and only invokes this method (which mutates the refund ledger)
     * once the human approves. A denied, timed-out, or un-wired approval never
     * reaches this body, so no money moves without sign-off.
     */
    @AiTool(name = "issue_refund",
            description = "Issue a refund for an order. This moves money and posts to the ledger immediately.")
    @RequiresApproval("This refund posts immediately. Approve?")
    public String issueRefund(
            @Param(value = "orderId", description = "The order id to refund (e.g., A-1001)")
            String orderId) {
        long amountCents = ORDER_TOTALS_CENTS.getOrDefault(orderId, DEFAULT_REFUND_CENTS);
        long newTotal = refundLedgerCents.merge(orderId, amountCents, Long::sum);
        return String.format("Refund of %s posted for order %s (ledger total for order: %s).",
                formatDollars(amountCents), orderId, formatDollars(newTotal));
    }

    /**
     * Total cents refunded so far for an order. Lets callers (and tests) observe
     * whether {@link #issueRefund(String)} actually posted — the side effect the
     * {@code @RequiresApproval} gate is supposed to withhold until approval.
     */
    public long refundedCents(String orderId) {
        return refundLedgerCents.getOrDefault(orderId, 0L);
    }

    /** Number of distinct orders with at least one posted refund. */
    public int refundCount() {
        return refundLedgerCents.size();
    }

    private static String formatDollars(long cents) {
        return String.format("$%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    @AiTool(name = "convert_temperature",
            description = "Converts a temperature between Celsius and Fahrenheit")
    public String convertTemperature(
            @Param(value = "value", description = "The temperature value to convert")
            double value,
            @Param(value = "from_unit", description = "Source unit: 'C' for Celsius or 'F' for Fahrenheit")
            String fromUnit) {
        if ("C".equalsIgnoreCase(fromUnit) || "celsius".equalsIgnoreCase(fromUnit)) {
            double fahrenheit = value * 9.0 / 5.0 + 32;
            return String.format("%.1f°C = %.1f°F", value, fahrenheit);
        } else {
            double celsius = (value - 32) * 5.0 / 9.0;
            return String.format("%.1f°F = %.1f°C", value, celsius);
        }
    }
}
