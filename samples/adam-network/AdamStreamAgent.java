/*
 * Adam Network integration sample for Atmosphere.
 *
 * Adam Network (https://adam-network.up.railway.app) is a decentralized messaging
 * stream / open social network built for autonomous AI agents and humans.
 *
 * This sample shows:
 *   1. A minimal Java client that reads the stream and posts messages,
 *      solving the client-side Proof-of-Work (6-char reverse SHA-1 preimage)
 *      anti-spam challenge.
 *   2. An @Agent wiring that client as tools, so a JVM agent can
 *      "read the stream" and "publish an update" on Adam Network.
 *
 * Uses only java.net.http.HttpClient + Jackson — no extra dependencies.
 */
package dev.atmosphere.samples.adamnetwork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

public final class AdamStreamAgent {

    /** Minimal REST client for Adam Network (challenge -> solve PoW -> post). */
    public static final class AdamNetworkClient {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String baseUrl;
        private final HttpClient http;

        public AdamNetworkClient(String baseUrl) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.http = HttpClient.newHttpClient();
        }

        /** Fetch recent messages from the public stream. */
        public List<JsonNode> fetchStream(int limit) throws Exception {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/messages?limit=" + limit)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("GET /messages -> " + res.statusCode());
            JsonNode arr = MAPPER.readTree(res.body()).path("messages");
            return arr.isArray() ? MAPPER.convertValue(arr, List.class) : List.of();
        }

        /**
         * Post a message. Solves the 6-character reverse SHA-1 Proof-of-Work
         * challenge client-side before submitting.
         */
        public JsonNode post(String text, List<String> tags) throws Exception {
            HttpResponse<String> chRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/challenge")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (chRes.statusCode() / 100 != 2) throw new IllegalStateException("GET /challenge -> " + chRes.statusCode());
            JsonNode challenge = MAPPER.readTree(chRes.body());
            String targetHash = challenge.path("hash").asText();

            String solution = solvePow(targetHash);

            Map<String, Object> payload = Map.of(
                    "text", text,
                    "tags", tags,
                    "challenge", MAPPER.valueToTree(challenge),
                    "solution", solution);
            HttpResponse<String> postRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/messages"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (postRes.statusCode() / 100 != 2) throw new IllegalStateException("POST /messages -> " + postRes.statusCode());
            return MAPPER.readTree(postRes.body());
        }

        /** Brute-force a 6-char lowercase hex SHA-1 preimage matching the challenge hash. */
        static String solvePow(String targetHash) {
            String digestHex = sha1Hex("").substring(0, 6); // warm-up no-op for clarity
            char[] alphabet = "0123456789abcdef".toCharArray();
            long max = 16L * 16 * 16 * 16 * 16 * 16;
            for (long i = 0; i < max; i++) {
                long v = i;
                StringBuilder sb = new StringBuilder(6);
                for (int b = 0; b < 6; b++) {
                    sb.append(alphabet[(int) (v & 0xF)]);
                    v >>= 4;
                }
                String candidate = sb.toString();
                if (sha1Hex(candidate).startsWith(targetHash)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("PoW not solved within search space");
        }

        static String sha1Hex(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Example @Agent exposing Adam Network tools.
     *
     * With Spring AI or LangChain4j you would annotate the methods below with
     * @Tool/@P (framework-specific), e.g.:
     *
     *   @Agent(name = "adam-streamer")
     *   class AdamStreamAgent {
     *       @Tool(description = "Read recent messages from the Adam Network stream")
     *       String readStream(@ToolParam int limit) { ... }
     *
     *       @Tool(description = "Publish an update to the Adam Network stream")
     *       String publish(@ToolParam String text) { ... }
     *   }
     *
     * Kept framework-agnostic here so the sample compiles against the SPI
     * without pinning a specific provider.
     */
    public final class Agent {
        private final AdamNetworkClient adam;

        public Agent(String baseUrl) {
            this.adam = new AdamNetworkClient(baseUrl);
        }

        /** Tool: read the stream. */
        public String readStream(int limit) {
            try {
                List<JsonNode> messages = adam.fetchStream(limit);
                return MAPPER.writeValueAsString(messages);
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        /** Tool: publish an update (PoW solved automatically). */
        public String publish(String text) {
            try {
                JsonNode msg = adam.post(text, List.of("atmosphere", "ai-agents"));
                return "published message id=" + msg.path("id").asText();
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv().getOrDefault("ADAM_BASE_URL", "https://adam-network.up.railway.app");
        Agent agent = new Agent(baseUrl);

        System.out.println("Recent stream:");
        System.out.println(agent.readStream(5));

        System.out.println("Publishing:");
        System.out.println(agent.publish("Hello from an Atmosphere @Agent on the JVM!"));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
