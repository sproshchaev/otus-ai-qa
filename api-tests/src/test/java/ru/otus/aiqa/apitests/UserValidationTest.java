package ru.otus.aiqa.apitests;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Пользователи: валидация входных данных")
class UserValidationTest extends ApiTestBase {

    @ParameterizedTest(name = "возраст {0} принимается")
    @ValueSource(ints = {18, 19, 119, 120})
    void acceptsAgeInsideBoundaries(int age) {
        given(spec)
                .body(userJson("Граничный Кейс", "boundary%d@example.com".formatted(age), age))
                .when()
                .post("/api/users")
                .then()
                .statusCode(201);
    }

    @ParameterizedTest(name = "возраст {0} отклоняется")
    @ValueSource(ints = {17, 121, 0, -1})
    void rejectsAgeOutsideBoundaries(int age) {
        given(spec)
                .body(userJson("Граничный Кейс", "reject@example.com", age))
                .when()
                .post("/api/users")
                .then()
                .statusCode(400);
    }

    @ParameterizedTest(name = "имя длиной {1} символов: ожидаем {2}")
    @CsvSource({"И,1,400", "Ян,2,201", "ИванИванИванИванИванИванИванИванИванИванИванИванИв,50,201"})
    void checksNameLength(String name, int length, int expectedStatus) {
        given(spec)
                .body(userJson(name, "name%d@example.com".formatted(length), 30))
                .when()
                .post("/api/users")
                .then()
                .statusCode(expectedStatus);
    }

    @ParameterizedTest(name = "email «{0}» отклоняется")
    @ValueSource(strings = {"без-собаки", "@example.com", "два@@example.com"})
    void rejectsMalformedEmail(String email) {
        given(spec)
                .body(userJson("Иван Петров", email, 30))
                .when()
                .post("/api/users")
                .then()
                .statusCode(400);
    }
}
