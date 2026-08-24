package ru.otus.aiqa.apitests;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

/** Тесты ходят в поднятый сервис по HTTP: адрес задаётся -Dservice.url, по умолчанию localhost:8080. */
abstract class ApiTestBase {

    protected static RequestSpecification spec;

    @BeforeAll
    static void setUp() {
        String baseUri = System.getProperty("service.url", "http://localhost:8080");
        spec = new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .build();
        RestAssured.requestSpecification = spec;
    }

    protected static String userJson(String name, String email, int age) {
        return """
                {"name": "%s", "email": "%s", "age": %d}
                """.formatted(name, email, age);
    }

    protected static int createUser(String name, String email, int age) {
        return io.restassured.RestAssured.given(spec)
                .body(userJson(name, email, age))
                .post("/api/users")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
