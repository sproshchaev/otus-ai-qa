package ru.otus.aiqa.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Контракт проверяется тестами, потому что нарушает его не человек, а модель,
 * и нарушает по-разному от прогона к прогону.
 */
class ReviewValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        return ReviewValidator.validate(MAPPER.readTree(json));
    }

    private static final String VALID = """
            {
              "summary": "Тесты зависят от порядка выполнения",
              "findings": [
                {
                  "file": "api-tests/src/test/java/T.java",
                  "line": 42,
                  "severity": "major",
                  "category": "coverage",
                  "finding": "Негативный случай не проверяется",
                  "suggestion": "Добавьте тест на пустое имя",
                  "confidence": 0.8
                }
              ]
            }
            """;

    @Test
    @DisplayName("Корректный ответ проходит контракт")
    void acceptsValid() throws Exception {
        assertTrue(validate(VALID).isEmpty(), "у корректного ответа не должно быть замечаний");
    }

    @Test
    @DisplayName("Пустой список находок допустим: молчание — тоже результат ревью")
    void acceptsEmptyFindings() throws Exception {
        assertTrue(validate("{\"summary\":\"замечаний нет\",\"findings\":[]}").isEmpty(),
                "пустой findings контракт не нарушает");
    }

    @Test
    @DisplayName("Находка без предложения, что делать, контракт не проходит")
    void rejectsFindingWithoutSuggestion() throws Exception {
        List<String> problems = validate(VALID.replace("\"Добавьте тест на пустое имя\"", "\"\""));
        assertEquals(1, problems.size(), "ожидалось ровно одно замечание");
        assertTrue(problems.get(0).contains("suggestion"), "замечание должно называть поле suggestion");
    }

    @Test
    @DisplayName("Неизвестная важность и номер строки вне диапазона отбраковываются")
    void rejectsUnknownSeverityAndBadLine() throws Exception {
        List<String> problems = validate(VALID
                .replace("\"severity\": \"major\"", "\"severity\": \"critical\"")
                .replace("\"line\": 42", "\"line\": 0"));
        assertEquals(2, problems.size(), "ожидались два замечания: severity и line");
    }

    @Test
    @DisplayName("Уверенность вне диапазона 0..1 отбраковывается")
    void rejectsConfidenceOutOfRange() throws Exception {
        assertTrue(validate(VALID.replace("0.8", "1.4")).stream()
                        .anyMatch(p -> p.contains("confidence")),
                "confidence больше единицы контракт нарушает");
    }

    @Test
    @DisplayName("Текст вместо объекта отбраковывается сразу")
    void rejectsNonObject() throws Exception {
        assertEquals(List.of("ответ не является объектом JSON"), validate("\"всё хорошо\""),
                "строка вместо объекта — одно замечание и выход");
    }
}
