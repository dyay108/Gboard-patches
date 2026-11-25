package dev.jason.gboardpatches.extension.aibar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AiBarLlmTest {
    private ServerSocket server;
    private Thread serverThread;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String response = "";
    private volatile int delayMillis = 0;

    /** A minimal one-request-at-a-time HTTP/1.1 server: enough to check what the clients send. */
    @Before public void start() throws Exception {
        server = new ServerSocket(0);
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = in.readLine();
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    char[] body = new char[length];
                    int read = 0;
                    while (read < length) read += in.read(body, read, length - read);
                    synchronized (requests) {
                        requests.add(Map.of("path", requestLine.split(" ")[1], "headers", headers, "body", new String(body, 0, read)));
                    }
                    try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                            + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(bytes);
                    out.flush();
                } catch (Exception ignored) {
                    // Closed or timed-out client: keep serving.
                }
            }
        });
        serverThread.start();
    }

    @After public void stop() throws Exception { server.close(); }

    private String base() { return "http://127.0.0.1:" + server.getLocalPort(); }

    @SuppressWarnings("unchecked")
    private String header(int index, String name) {
        return ((Map<String, String>) requests.get(index).get("headers")).get(name.toLowerCase(Locale.ROOT));
    }

    private JSONObject body(int index) throws Exception { return new JSONObject((String) requests.get(index).get("body")); }

    private AiBarLlm.Client anthropic(String key, String model) { return new AiBarLlm.Anthropic(base(), key, model, 2000); }
    private AiBarLlm.Client openAi(String key, String model) { return new AiBarLlm.OpenAiCompatible(base() + "/v1/", key, model, 2000); }

    @Test public void anthropicSendsTheDocumentedRequest() throws Exception {
        response = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":\"Hola \"},{\"type\":\"text\",\"text\":\"mundo\"}],\"stop_reason\":\"end_turn\"}";
        assertEquals("Hola mundo", anthropic("sk-ant-test", "claude-x").complete("Be terse.", "Translate: hi"));
        assertEquals("/v1/messages", requests.get(0).get("path"));
        assertEquals("sk-ant-test", header(0, "X-api-key"));
        assertEquals("2026-10-21", header(0, "Anthropic-version"));
        JSONObject body = body(0);
        assertEquals("claude-x", body.getString("model"));
        assertEquals("Be terse.", body.getString("system"));
        assertEquals(4096, body.getInt("max_tokens"));
        assertEquals("Translate: hi", body.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertFalse(body.has("temperature"));
    }

    @Test public void anthropicErrorsAndRefusals() {
        status = 401;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        AiBarException error = assertThrows(AiBarException.class, () -> anthropic("k", "m").complete("s", "p"));
        assertEquals("Anthropic error 401: invalid x-api-key", error.getMessage());
        status = 200;
        response = "{\"content\":[],\"stop_reason\":\"refusal\"}";
        assertTrue(assertThrows(AiBarException.class, () -> anthropic("k", "m").complete("s", "p")).getMessage().contains("declined"));
    }

    @Test public void missingModelOrKeyFailsWithoutARequest() {
        assertEquals("Set a model for Anthropic in AI Bar settings",
                assertThrows(AiBarException.class, () -> anthropic("k", " ").complete("s", "p")).getMessage());
        assertEquals("Add your Anthropic API key in AI Bar settings",
                assertThrows(AiBarException.class, () -> anthropic("", "m").complete("s", "p")).getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test public void openAiCompatibleSendsAChatCompletion() throws Exception {
        response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hola\",\"refusal\":null},\"finish_reason\":\"stop\"}]}";
        assertEquals("hola", openAi("sk-test", "gpt-x").complete("Be terse.", "Translate: hi"));
        assertEquals("/v1/chat/completions", requests.get(0).get("path"));
        assertEquals("Bearer sk-test", header(0, "Authorization"));
        JSONObject body = body(0);
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("Translate: hi", body.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test public void openAiCompatibleWorksWithoutAKey() throws Exception {
        response = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        assertEquals("ok", openAi(null, "m").complete("s", "p"));
        assertNull(header(0, "Authorization"));
    }

    @Test public void openAiCompatibleErrors() {
        status = 404;
        response = "{\"error\":{\"message\":\"The model `nope` does not exist\"}}";
        assertEquals("API error 404: The model `nope` does not exist",
                assertThrows(AiBarException.class, () -> openAi("k", "m").complete("s", "p")).getMessage());
    }

    @Test public void slowServersTimeOut() {
        delayMillis = 3000;
        response = "{}";
        assertEquals("The AI didn't answer in time",
                assertThrows(AiBarException.class, () -> openAi("k", "m").complete("s", "p")).getMessage());
    }
}
