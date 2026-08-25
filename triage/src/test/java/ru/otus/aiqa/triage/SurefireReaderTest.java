package ru.otus.aiqa.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Чтение отчёта Surefire")
class SurefireReaderTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Demo" tests="2" failures="1" time="1.5">
              <testcase name="green" classname="ru.demo.OkTest" time="0.5"/>
              <testcase name="red" classname="ru.demo.BadTest" time="1.0">
                <failure type="org.opentest4j.AssertionFailedError" message="expected 7 but was 6">
            org.opentest4j.AssertionFailedError: expected 7 but was 6
                </failure>
              </testcase>
            </testsuite>
            """;

    @Test
    @DisplayName("Считаны все кейсы, падения отмечены, время просуммировано")
    void readsCasesAndFailures(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TEST-ru.demo.DemoTest.xml"), REPORT);

        var report = SurefireReader.read(dir);

        assertEquals(2, report.total());
        assertEquals(1, report.failed());
        assertEquals(1.5, report.totalSeconds(), 0.001);
        assertTrue(report.cases().stream().anyMatch(c -> c.failed() && c.name().equals("red")));
    }

    @Test
    @DisplayName("Пустая директория — понятная ошибка, а не пустой разбор")
    void failsOnEmptyDirectory(@TempDir Path dir) {
        var error = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> SurefireReader.read(dir));

        assertTrue(error.getMessage().contains("не найдено"));
    }
}
