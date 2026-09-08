package ru.otus.aiqa.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Правила — та часть шага, которая обязана быть воспроизводимой.
 * Поэтому у неё тесты, а не «посмотрели глазами на демо».
 */
class SmellDetectorTest {

    private static Set<String> rules(String source) {
        return SmellDetector.scan(source).stream()
                .map(SmellDetector.Smell::rule)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Thread.sleep находится и получает важность major")
    void findsSleep() {
        List<SmellDetector.Smell> found = SmellDetector.scan("""
                class T {
                    void a() throws Exception {
                        Thread.sleep(3000);
                    }
                }
                """);
        assertEquals(1, found.size(), "ожидалась ровно одна находка");
        assertEquals("sleep", found.get(0).rule(), "правило должно быть sleep");
        assertEquals(3, found.get(0).line(), "номер строки должен указывать на вызов");
        assertEquals("major", found.get(0).severity(), "ожидание временем — major");
    }

    @Test
    @DisplayName("Ассерт без сообщения отличается от ассерта с сообщением")
    void distinguishesAssertMessage() {
        assertTrue(rules("assertTrue(x != null);").contains("assert-without-message"),
                "assertTrue с одним аргументом — находка");
        assertTrue(rules("assertEquals(201, r.statusCode());").contains("assert-without-message"),
                "assertEquals с двумя аргументами — находка");
        assertFalse(rules("assertEquals(201, r.statusCode(), \"создание отвечает 201\");")
                        .contains("assert-without-message"),
                "с сообщением находки быть не должно");
        assertFalse(rules("assertTrue(list.contains(\"a\"), \"элемент должен найтись\");")
                        .contains("assert-without-message"),
                "вложенный вызов не должен считаться за сообщение");
    }

    @Test
    @DisplayName("Пустой catch ловится и в одну строку, и в несколько")
    void findsSwallowedException() {
        assertTrue(rules("try { a(); } catch (Exception e) {}").contains("swallowed-exception"),
                "однострочный пустой catch");
        assertTrue(rules("""
                try {
                    a();
                } catch (Exception e) {
                }
                """).contains("swallowed-exception"), "многострочный пустой catch");
        assertFalse(rules("""
                try {
                    a();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                """).contains("swallowed-exception"), "непустой catch находкой не является");
    }

    @Test
    @DisplayName("Порядок, общее состояние, адрес стенда и пустое имя")
    void findsOrderStateUrlAndName() {
        Set<String> found = rules("""
                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                class T {
                    private static String createdUserId;

                    @Test
                    @Order(1)
                    void test1() {
                        given().baseUri("http://localhost:8080").get("/api/users");
                    }
                }
                """);
        assertTrue(found.contains("order-dependence"), "явный порядок должен находиться");
        assertTrue(found.contains("shared-mutable-state"), "статическое изменяемое поле должно находиться");
        assertTrue(found.contains("hardcoded-url"), "зашитый адрес должен находиться");
        assertTrue(found.contains("meaningless-name"), "имя test1 должно находиться");
    }

    @Test
    @DisplayName("Константа static final общим состоянием не считается")
    void ignoresStaticFinal() {
        assertFalse(rules("private static final String BASE = System.getProperty(\"app.base\");")
                        .contains("shared-mutable-state"),
                "неизменяемая константа — не общее состояние");
    }

    @Test
    @DisplayName("Три одинаковых блока подряд считаются копипастой")
    void findsCopyPaste() {
        String block = """
                    Response r = given()
                            .baseUri(BASE)
                            .body("{}")
                            .post("/api/users");
                """;
        assertTrue(rules(block + block + block).contains("copy-paste"),
                "повтор блока трижды — находка");
        assertFalse(rules(block + block).contains("copy-paste"),
                "двух повторов для находки мало");
    }

    @Test
    @DisplayName("На аккуратном тесте правила молчат — это и есть граница их применимости")
    void quietOnCleanTest() {
        assertTrue(SmellDetector.scan("""
                class UserSearchTest {

                    private static final String BASE = System.getProperty("app.base");

                    @Test
                    @DisplayName("Поиск отвечает 200")
                    void searchAnswersOk() {
                        Response response = given().baseUri(BASE).get("/api/users");
                        assertEquals(200, response.statusCode(), "поиск должен отвечать 200");
                    }
                }
                """).isEmpty(), "на чистом файле находок быть не должно");
    }
}
