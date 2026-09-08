package ru.otus.aiqa.apitests.smelly;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Второй учебный набор занятия 19: здесь правила молчат.
 * Ни Thread.sleep, ни голых ассертов, ни зависимости от порядка —
 * набор проходит формальную проверку и при этом дорог в сопровождении.
 * На нём видно, где заканчивается линтер и начинается ревью по существу.
 */
@Disabled("учебная фикстура: вход для ИИ-ревью, не часть регрессии")
class UserSearchQuietTest {

    private static final String BASE = System.getProperty("app.base", "http://localhost:8080");

    @Test
    @DisplayName("Поиск пользователя возвращает корректный ответ")
    void searchReturnsCorrectResponse() {
        Response response = given().baseUri(BASE).get("/api/users?page=0&size=20");

        assertEquals(200, response.statusCode(), "поиск должен отвечать 200");
        assertEquals(
                "{\"content\":[],\"page\":0,\"size\":20,\"total\":0}",
                response.asString(),
                "тело ответа должно совпасть с эталоном");
    }

    @Test
    @DisplayName("Создание пользователя и его поиск")
    void createAndSearch() {
        Response created = given()
                .baseUri(BASE)
                .contentType("application/json")
                .body("{\"name\":\"Sergey\",\"email\":\"s@example.com\"}")
                .post("/api/users");
        assertEquals(201, created.statusCode(), "создание должно отвечать 201");

        Response found = given().baseUri(BASE).get("/api/users?page=0&size=20");
        if (found.statusCode() == 200) {
            assertEquals(1, found.jsonPath().getInt("total"), "должен найтись один пользователь");
        }
    }

    @Test
    @DisplayName("Валидация пустого имени")
    void validationRejectsEmptyName() {
        Response response = given()
                .baseUri(BASE)
                .contentType("application/json")
                .body("{\"name\":\"\",\"email\":\"s@example.com\"}")
                .post("/api/users");

        assertEquals(400, response.statusCode(), "пустое имя должно отклоняться");
    }
}
