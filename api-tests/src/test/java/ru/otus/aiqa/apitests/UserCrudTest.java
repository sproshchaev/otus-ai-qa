package ru.otus.aiqa.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Пользователи: создание, чтение, обновление, удаление")
class UserCrudTest extends ApiTestBase {

    @Test
    @DisplayName("POST /api/users создаёт пользователя и возвращает 201 с идентификатором")
    void createsUser() {
        given(spec)
                .body(userJson("Иван Петров", "ivan@example.com", 33))
                .when()
                .post("/api/users")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("Иван Петров"))
                .body("active", equalTo(false));
    }

    @Test
    @DisplayName("GET /api/users/{id} возвращает созданного пользователя")
    void readsUser() {
        int id = createUser("Мария Ким", "maria@example.com", 29);

        given(spec)
                .when()
                .get("/api/users/{id}", id)
                .then()
                .statusCode(200)
                .body("email", equalTo("maria@example.com"));
    }

    @Test
    @DisplayName("GET /api/users/{id} на несуществующем идентификаторе отвечает 404")
    void returnsNotFoundForUnknownUser() {
        given(spec)
                .when()
                .get("/api/users/{id}", 999_999)
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("PUT /api/users/{id} обновляет поля пользователя")
    void updatesUser() {
        int id = createUser("Олег Сидоров", "oleg@example.com", 41);

        given(spec)
                .body(userJson("Олег Сидоров", "oleg.new@example.com", 42))
                .when()
                .put("/api/users/{id}", id)
                .then()
                .statusCode(200)
                .body("email", equalTo("oleg.new@example.com"))
                .body("age", equalTo(42));
    }

    @Test
    @DisplayName("DELETE /api/users/{id} удаляет пользователя и второй раз отвечает 404")
    void deletesUser() {
        int id = createUser("Анна Волкова", "anna@example.com", 25);

        given(spec).when().delete("/api/users/{id}", id).then().statusCode(204);
        given(spec).when().delete("/api/users/{id}", id).then().statusCode(404);
    }

    @Test
    @DisplayName("POST /api/users/{id}/activate переводит пользователя в активные")
    void activatesUser() {
        int id = createUser("Пётр Егоров", "petr@example.com", 37);

        given(spec)
                .when()
                .post("/api/users/{id}/activate", id)
                .then()
                .statusCode(200)
                .body("active", equalTo(true));
    }
}
