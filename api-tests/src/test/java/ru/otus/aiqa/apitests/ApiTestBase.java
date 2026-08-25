package ru.otus.aiqa.apitests;

import static io.restassured.RestAssured.given;

import io.restassured.builder.RequestSpecBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

/** Тесты ходят в поднятый сервис по HTTP: адрес задаётся -Dservice.url, по умолчанию localhost:8080. */
abstract class ApiTestBase {

    protected static RequestSpecification spec;

    @BeforeAll
    static void setUp() {
        String baseUri = System.getProperty("service.url", "http://localhost:8080");
        requireServiceIsUp(baseUri);
        spec = new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .build();
    }

    /**
     * Тесты бессмысленны без сервиса, поэтому проверяем его один раз и падаем с внятным текстом,
     * а не двумя десятками ConnectException подряд.
     */
    private static void requireServiceIsUp(String baseUri) {
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(baseUri + "/api/users"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("сервис ответил " + response.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Сервис не отвечает на " + baseUri + " — поднимите его перед прогоном:\n"
                    + "  java -jar user-service/target/user-service-*.jar &\n"
                    + "Другой адрес задаётся через -Dservice.url. Подробности в README.", e);
        }
    }

    protected static String userJson(String name, String email, int age) {
        return """
                {"name": "%s", "email": "%s", "age": %d}
                """.formatted(name, email, age);
    }

    protected static int createUser(String name, String email, int age) {
        return given(spec)
                .body(userJson(name, email, age))
                .post("/api/users")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
