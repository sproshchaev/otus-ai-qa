package ru.otus.aiqa.apitests.smelly;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Учебный набор для занятия 19: тесты написаны намеренно плохо.
 * Это вход для инструмента ревью, а не образец — запускать их нечего,
 * поэтому класс помечен @Disabled: сломанная фикстура не имеет права
 * красить общий конвейер.
 */
@Disabled("учебная фикстура: вход для ИИ-ревью, не часть регрессии")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CartCheckoutSmellyTest {

    private static String createdUserId;

    @Test
    @Order(1)
    void test1() throws Exception {
        Response r = given()
                .baseUri("http://localhost:8080")
                .contentType("application/json")
                .body("{\"name\":\"Ivan\",\"email\":\"ivan@example.com\"}")
                .post("/api/users");
        Thread.sleep(3000);
        assertEquals(201, r.statusCode());
        createdUserId = r.jsonPath().getString("id");
        assertTrue(createdUserId != null);
    }

    @Test
    @Order(2)
    void test2() {
        Response r = given()
                .baseUri("http://localhost:8080")
                .contentType("application/json")
                .get("/api/users/" + createdUserId);
        assertEquals(200, r.statusCode());
        assertTrue(r.asString().contains("Ivan"));
    }

    @Test
    @Order(3)
    void test3() {
        Response r = given()
                .baseUri("http://localhost:8080")
                .contentType("application/json")
                .body("{\"name\":\"Petr\",\"email\":\"petr@example.com\"}")
                .post("/api/users");
        assertEquals(201, r.statusCode());
    }

    @Test
    @Order(4)
    void test4() {
        Response r = given()
                .baseUri("http://localhost:8080")
                .contentType("application/json")
                .body("{\"name\":\"Anna\",\"email\":\"anna@example.com\"}")
                .post("/api/users");
        assertEquals(201, r.statusCode());
    }

    @Test
    @Order(5)
    void test5() {
        try {
            Response r = given()
                    .baseUri("http://localhost:8080")
                    .delete("/api/users/" + createdUserId);
            assertEquals(204, r.statusCode());
        } catch (Exception e) {
        }
    }
}
