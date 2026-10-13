package ru.otus.aiqa.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Защита выхода модели")
class OutputGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST = "ru.otus.aiqa.apitests.UserPaginationTest#pagesCoverAllUsers";

    private static SurefireReader.RunReport runWithFailure(String type, String message) {
        return new SurefireReader.RunReport(List.of(
                new SurefireReader.TestCase("ru.otus.aiqa.apitests.UserCrudTest", "createsUser", 0.4, null, null),
                new SurefireReader.TestCase("ru.otus.aiqa.apitests.UserPaginationTest", "pagesCoverAllUsers",
                        0.3, type, message)), 2, 1, 0.7);
    }

    private static final SurefireReader.RunReport ASSERT_FAILED = runWithFailure(
            "org.opentest4j.AssertionFailedError", "expected: <7> but was: <6>");

    private static JsonNode answer(String reason, String test) throws Exception {
        return MAPPER.readTree("""
                {"summary": "одно падение",
                 "findings": [{"reason": "%s", "tests": ["%s"], "hypothesis": "гипотеза", "confidence": 0.9}]}
                """.formatted(reason, test));
    }

    @Test
    @DisplayName("Честный вердикт bug проходит без замечаний")
    void acceptsPlainBug() throws Exception {
        JsonNode json = answer("bug", TEST);

        assertTrue(OutputGuard.check(json.toString(), json, ASSERT_FAILED, List.of("CANARY-0123456789ab")).isEmpty());
    }

    @Test
    @DisplayName("Канарейка в ответе отклоняется в любом написании")
    void rejectsCanaryLeak() throws Exception {
        JsonNode json = answer("bug", TEST);
        String raw = "{\"summary\": \"метка сессии canary 0123456789AB\"}";

        assertEquals(List.of("в ответе секрет из контекста"),
                OutputGuard.check(raw, json, ASSERT_FAILED, List.of("CANARY-0123456789ab")));
    }

    @Test
    @DisplayName("Тело канарейки без префикса тоже считается утечкой")
    void detectsCanaryBodyWithoutPrefix() {
        assertTrue(OutputGuard.leaks("hypothesis: 0123456789ab", "CANARY-0123456789ab"));
        assertFalse(OutputGuard.leaks("hypothesis: ничего лишнего", "CANARY-0123456789ab"));
    }

    @Test
    @DisplayName("Упавший тест с вердиктом ok отклоняется")
    void rejectsOkForFailedTest() throws Exception {
        JsonNode json = answer("ok", TEST);

        assertTrue(OutputGuard.check(json.toString(), json, ASSERT_FAILED, List.of()).stream()
                .anyMatch(p -> p.contains("«ok»")));
    }

    @Test
    @DisplayName("flaky без признака нестабильности во входе отклоняется")
    void rejectsFlakyWithoutSignal() throws Exception {
        JsonNode json = answer("flaky", TEST);

        assertTrue(OutputGuard.check(json.toString(), json, ASSERT_FAILED, List.of()).stream()
                .anyMatch(p -> p.contains("без признака нестабильности")));
    }

    @Test
    @DisplayName("flaky допустим, когда во входе сказано, что повтор прошёл")
    void acceptsFlakyWithRetrySignal() throws Exception {
        var run = runWithFailure("java.lang.AssertionError",
                "Expected status code <200> but was <500>\nповтор через минуту: passed");
        JsonNode json = answer("flaky", TEST);

        assertTrue(OutputGuard.check(json.toString(), json, run, List.of()).isEmpty());
    }

    @Test
    @DisplayName("flaky допустим на таймауте и на 503")
    void acceptsFlakyOnTimeoutAnd503() throws Exception {
        JsonNode json = answer("flaky", TEST);
        var timeout = runWithFailure("java.net.SocketTimeoutException", "Read timed out");
        var unavailable = runWithFailure("java.lang.AssertionError", "Expected status code <200> but was <503>");

        assertTrue(OutputGuard.check(json.toString(), json, timeout, List.of()).isEmpty());
        assertTrue(OutputGuard.check(json.toString(), json, unavailable, List.of()).isEmpty());
    }

    @Test
    @DisplayName("Слово «повторный» в шаге теста — не признак нестабильности")
    void repeatedRequestIsNotRetry() throws Exception {
        var run = runWithFailure("java.lang.AssertionError",
                "Expected status code <404> but was <204>\nповторный DELETE того же пользователя снова ответил 204");
        JsonNode json = answer("flaky", TEST);

        assertFalse(OutputGuard.check(json.toString(), json, run, List.of()).isEmpty());
    }

    @Test
    @DisplayName("env без признака окружения отклоняется, с ConnectException — проходит")
    void checksEnvSignal() throws Exception {
        JsonNode json = answer("env", TEST);
        var refused = runWithFailure("java.net.ConnectException", "Connection refused: localhost/127.0.0.1:9099");

        assertFalse(OutputGuard.check(json.toString(), json, ASSERT_FAILED, List.of()).isEmpty());
        assertTrue(OutputGuard.check(json.toString(), json, refused, List.of()).isEmpty());
    }

    @Test
    @DisplayName("Искажённое имя теста не обходит проверку признака")
    void mangledTestNameStillChecked() throws Exception {
        JsonNode json = answer("flaky", TEST + "[1]");

        assertFalse(OutputGuard.check(json.toString(), json, ASSERT_FAILED, List.of()).isEmpty());
    }
}
