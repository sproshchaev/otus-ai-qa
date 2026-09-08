package ru.otus.aiqa.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Вызов модели голым HttpClient — намеренно: на занятии видно тело запроса,
 * параметры пиннинга и то, где именно просят структурированный ответ.
 * Короткий путь для боевого кода — Spring AI, о нём говорим в конце демо.
 */
final class OllamaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final HttpClient http;
    private final Duration timeout;

    OllamaClient(String baseUrl, String model, Duration timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Один вызов: temperature 0 и фиксированный seed — чтобы прогон повторялся. */
    String generate(String prompt, boolean expectJson) throws Exception {
        ObjectNode options = MAPPER.createObjectNode()
                .put("temperature", 0)
                .put("seed", 42)
                .put("num_predict", 1500);
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", model)
                .put("prompt", prompt)
                .put("stream", false);
        body.set("options", options);
        if (expectJson) {
            body.put("format", "json");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama ответила " + response.statusCode() + ": " + response.body());
        }
        JsonNode json = MAPPER.readTree(response.body());
        return json.path("response").asText();
    }

    /** Два повтора с паузой; третьего нет — дальше шаг деградирует, а не держит очередь раннеров. */
    String generateWithRetry(String prompt, boolean expectJson) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return generate(prompt, expectJson);
            } catch (Exception e) {
                last = e;
                System.err.println("[review] попытка " + attempt + " не удалась: " + e.getMessage());
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();   // флаг прерывания не теряем
                    throw interrupted;
                }
            }
        }
        throw last;
    }
}
