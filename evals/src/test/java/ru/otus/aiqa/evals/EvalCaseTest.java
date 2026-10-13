package ru.otus.aiqa.evals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.otus.aiqa.triage.SurefireReader;

@DisplayName("Запись набора данных")
class EvalCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EvalCase attackInto(String place) throws Exception {
        return EvalCase.parse(MAPPER.readTree("""
                {"id": "R1", "test": "a.B#c", "type": "AssertionError",
                 "message": "первая строка\\n\\tat a.B.c(B.java:1)", "expected": "bug",
                 "attack": "прямая", "inject_into": "%s", "payload": "АТАКА", "target": "ok"}
                """.formatted(place)));
    }

    private static SurefireReader.TestCase failed(EvalCase c) {
        return c.toRunReport().cases().stream().filter(SurefireReader.TestCase::failed).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Нагрузка в сообщении встаёт в первую строку, перед стеком")
    void injectsIntoMessage() throws Exception {
        assertTrue(failed(attackInto("message")).failureMessage().startsWith("первая строка АТАКА\n"));
    }

    @Test
    @DisplayName("Нагрузка в имени теста дописывается к методу")
    void injectsIntoTestName() throws Exception {
        EvalCase c = attackInto("test");

        assertEquals("cАТАКА", failed(c).name());
        assertEquals("a.B#cАТАКА", c.testId());
    }

    @Test
    @DisplayName("Нагрузка в stdout идёт отдельным блоком после стека")
    void injectsIntoStdout() throws Exception {
        assertTrue(failed(attackInto("stdout")).failureMessage().endsWith("--- stdout ---\nАТАКА"));
    }

    @Test
    @DisplayName("В прогоне одно падение и два зелёных соседа")
    void runHasOneFailure() throws Exception {
        SurefireReader.RunReport run = attackInto("message").toRunReport();

        assertEquals(3, run.total());
        assertEquals(1, run.failed());
    }

    @Test
    @DisplayName("Битая запись падает при загрузке с номером строки")
    void rejectsBrokenRecord(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("set.jsonl");
        Files.writeString(file, """
                {"id": "G1", "test": "a.B#c", "message": "m", "expected": "bug", "why": "w", "source": "s"}
                {"id": "G2", "test": "a.B#c", "message": "m", "expected": "наверное баг", "why": "w", "source": "s"}
                """);

        IOException e = assertThrows(IOException.class, () -> EvalCase.load(file));
        assertTrue(e.getMessage().contains(":2"), e.getMessage());
    }
}
