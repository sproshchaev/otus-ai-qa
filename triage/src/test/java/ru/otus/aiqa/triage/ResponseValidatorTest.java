package ru.otus.aiqa.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Контракт ответа модели")
class ResponseValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID = """
            {"summary": "всё зелёное",
             "findings": [{"reason": "ok", "tests": ["A#b"], "hypothesis": "замечаний нет", "confidence": 0.9}]}
            """;

    @Test
    @DisplayName("Корректный ответ проходит без замечаний")
    void acceptsValidResponse() throws Exception {
        assertTrue(ResponseValidator.validate(MAPPER.readTree(VALID)).isEmpty());
    }

    @Test
    @DisplayName("Ответ без findings отклоняется")
    void rejectsMissingFindings() throws Exception {
        var problems = ResponseValidator.validate(MAPPER.readTree("{\"summary\": \"текст\"}"));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("findings"));
    }

    @Test
    @DisplayName("Неизвестная причина отклоняется: список причин закрытый")
    void rejectsUnknownReason() throws Exception {
        String json = VALID.replace("\"ok\"", "\"наверное баг\"");

        assertTrue(ResponseValidator.validate(MAPPER.readTree(json)).stream()
                .anyMatch(p -> p.contains("reason")));
    }

    @Test
    @DisplayName("Уверенность вне диапазона 0..1 отклоняется")
    void rejectsConfidenceOutsideRange() throws Exception {
        String json = VALID.replace("0.9", "42");

        assertTrue(ResponseValidator.validate(MAPPER.readTree(json)).stream()
                .anyMatch(p -> p.contains("confidence")));
    }

    @Test
    @DisplayName("Не-JSON и не-объект отклоняются, а не роняют шаг")
    void rejectsNonObject() throws Exception {
        assertEquals(1, ResponseValidator.validate(null).size());
        assertEquals(1, ResponseValidator.validate(MAPPER.readTree("[1,2,3]")).size());
    }
}
