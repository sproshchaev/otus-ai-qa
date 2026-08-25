package ru.otus.aiqa.apitests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Пользователи: постраничная выдача")
class UserPaginationTest extends ApiTestBase {

    @Test
    @DisplayName("Страницы не теряют элементы: сумма размеров страниц равна общему количеству")
    void pagesCoverAllUsers() {
        for (int i = 0; i < 7; i++) {
            createUser("Страничный " + i, "page%d@example.com".formatted(i), 30 + i);
        }

        int total = given(spec).queryParam("size", 3).get("/api/users").then()
                .statusCode(200).extract().path("total");

        int collected = 0;
        for (int page = 0; page * 3 < total; page++) {
            int onPage = given(spec)
                    .queryParam("page", page)
                    .queryParam("size", 3)
                    .get("/api/users")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("items.size()");
            collected += onPage;
        }

        assertEquals(total, collected,
                "постраничный обход должен вернуть ровно total элементов");
    }

    @Test
    @DisplayName("Страница за пределами данных возвращает пустой список, а не ошибку")
    void returnsEmptyPageBeyondData() {
        given(spec)
                .queryParam("page", 10_000)
                .queryParam("size", 20)
                .get("/api/users")
                .then()
                .statusCode(200)
                .body("items.size()", equalTo(0))
                .body("total", greaterThanOrEqualTo(0));
    }
}
