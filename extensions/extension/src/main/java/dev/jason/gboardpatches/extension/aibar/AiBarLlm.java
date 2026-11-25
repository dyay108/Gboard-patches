package dev.jason.gboardpatches.extension.aibar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The two AI providers, over plain HttpURLConnection (Gboard ships no HTTP library we can rely on). */
public final class AiBarLlm {
    private AiBarLlm() {}

    public interface Client {
        /** Blocking; call off the main thread. */
        String complete(String system, String prompt) throws AiBarException;
    }

    /** Claude Messages API: POST /v1/messages with anthropic-version 2026-10-21. */
    public static final class Anthropic implements Client {
        private static final String API_VERSION = "2026-10-21";
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final int timeoutMillis;

        public Anthropic(String baseUrl, String apiKey, String model, int timeoutMillis) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.model = model == null ? "" : model.trim();
            this.timeoutMillis = timeoutMillis;
        }

        @Override public String complete(String system, String prompt) throws AiBarException {
            if (model.isEmpty()) throw new AiBarException("Set a model for Anthropic in AI Bar settings");
            if (apiKey.isEmpty()) throw new AiBarException("Add your Anthropic API key in AI Bar settings");
            try {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("max_tokens", 4096)
                        .put("system", system)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
                Response response = post(trimEnd(baseUrl) + "/v1/messages", body.toString(), timeoutMillis,
                        "x-api-key", apiKey, "anthropic-version", API_VERSION);
                if (response.status < 200 || response.status > 299) {
                    throw new AiBarException(shorten("Anthropic error " + response.status + ": " + errorMessage(response.body)));
                }
                JSONObject json = parse(response.body, "Anthropic sent an unreadable answer");
                if ("refusal".equals(json.optString("stop_reason"))) throw new AiBarException("The model declined this request");
                StringBuilder text = new StringBuilder();
                JSONArray content = json.optJSONArray("content");
                if (content != null) {
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject block = content.optJSONObject(i);
                        // Thinking blocks are skipped; only text blocks count.
                        if (block != null && "text".equals(block.optString("type"))) text.append(block.optString("text"));
                    }
                }
                return text.toString();
            } catch (JSONException e) {
                throw new AiBarException("Anthropic sent an unreadable answer");
            }
        }
    }

    /** Any OpenAI-compatible Chat Completions endpoint; {@code baseUrl} is the part before /chat/completions. */
    public static final class OpenAiCompatible implements Client {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final int timeoutMillis;

        public OpenAiCompatible(String baseUrl, String apiKey, String model, int timeoutMillis) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.model = model == null ? "" : model.trim();
            this.timeoutMillis = timeoutMillis;
        }

        @Override public String complete(String system, String prompt) throws AiBarException {
            if (baseUrl.isEmpty()) throw new AiBarException("Set the endpoint URL in AI Bar settings");
            if (model.isEmpty()) throw new AiBarException("Set a model for the OpenAI-compatible endpoint in AI Bar settings");
            try {
                JSONObject body = new JSONObject().put("model", model).put("messages", new JSONArray()
                        // "system" works on every compatible server; OpenAI maps it to "developer" itself.
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", prompt)));
                Response response = apiKey.isEmpty()
                        ? post(trimEnd(baseUrl) + "/chat/completions", body.toString(), timeoutMillis)
                        : post(trimEnd(baseUrl) + "/chat/completions", body.toString(), timeoutMillis,
                                "Authorization", "Bearer " + apiKey);
                if (response.status < 200 || response.status > 299) {
                    throw new AiBarException(shorten("API error " + response.status + ": " + errorMessage(response.body)));
                }
                JSONObject json = parse(response.body, "The endpoint sent an unreadable answer");
                JSONArray choices = json.optJSONArray("choices");
                JSONObject message = choices == null || choices.length() == 0 ? null
                        : choices.getJSONObject(0).optJSONObject("message");
                if (message == null) throw new AiBarException("The endpoint sent an unreadable answer");
                String content = message.isNull("content") ? "" : message.optString("content", "");
                String refusal = message.isNull("refusal") ? "" : message.optString("refusal", "");
                if (content.isEmpty() && !refusal.isEmpty()) throw new AiBarException("The model declined this request");
                return content;
            } catch (JSONException e) {
                throw new AiBarException("The endpoint sent an unreadable answer");
            }
        }
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response post(String url, String body, int timeoutMillis, String... headers) throws AiBarException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) connection.setRequestProperty(headers[i], headers[i + 1]);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, stream == null ? "" : readAll(stream));
        } catch (SocketTimeoutException e) {
            throw new AiBarException("The AI didn't answer in time");
        } catch (UnknownHostException e) {
            throw new AiBarException("No internet connection");
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            throw new AiBarException("The endpoint URL isn't valid");
        } catch (IOException e) {
            throw new AiBarException(shorten("Network error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject parse(String body, String unreadable) throws AiBarException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new AiBarException(unreadable);
        }
    }

    /** Both APIs put errors in {"error": {"message": ...}}. */
    private static String errorMessage(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null && error.has("message")) return error.getString("message");
        } catch (JSONException ignored) {
            // Not JSON: fall through.
        }
        String trimmed = body.trim();
        return trimmed.isEmpty() || trimmed.startsWith("<") || trimmed.startsWith("{") ? "no details" : trimmed;
    }

    private static String trimEnd(String url) {
        String result = url.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    static String shorten(String message) {
        return message.length() > 120 ? message.substring(0, 117) + "…" : message;
    }
}
