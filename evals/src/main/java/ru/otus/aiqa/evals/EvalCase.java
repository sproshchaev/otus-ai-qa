package ru.otus.aiqa.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.otus.aiqa.triage.SurefireReader;

/**
 * Одна запись набора данных: падение теста и ожидаемый вердикт.
 * У записи набора атак есть ещё полезная нагрузка, место, куда она встраивается, и цель атаки.
 *
 * Формат — JSONL: одна запись на строку, без общего массива. Так набор дописывается строкой
 * в конец, а diff в ревью показывает ровно добавленные записи.
 */
record EvalCase(
        String id,
        String test,          // класс#метод
        String type,          // класс исключения
        String message,       // сообщение и короткий стек
        String stdout,        // необязательно: вывод теста или тело ответа сервиса
        String expected,      // bug | env | flaky
        String why,
        String source,
        String attack,        // только у атак: тип атаки
        String injectInto,    // только у атак: message | test | stdout
        String payload,       // только у атак: текст атаки
        String target) {      // только у атак: чего добивается — категория или canary

    static final Set<String> CATEGORIES = Set.of("bug", "env", "flaky");
    static final Set<String> PLACES = Set.of("message", "test", "stdout");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    boolean isAttack() {
        return attack != null;
    }

    String className() {
        return test.substring(0, test.indexOf('#'));
    }

    String methodName() {
        return test.substring(test.indexOf('#') + 1);
    }

    /** Имя теста так, как его увидит модель: у атаки через имя к методу дописана нагрузка. */
    String testId() {
        return className() + "#" + effectiveMethod();
    }

    private String effectiveMethod() {
        return isAttack() && "test".equals(injectInto) ? methodName() + payload : methodName();
    }

    /**
     * Вход шага — тот же RunReport, что triage строит из Surefire. Одно упавшее падение
     * и два зелёных соседа: на прогоне из одного падения модель видит слишком мало контекста,
     * а соседи всегда одни и те же, чтобы не влиять на сравнение между записями.
     */
    SurefireReader.RunReport toRunReport() {
        String text = message;
        if (isAttack() && "message".equals(injectInto)) {
            int lineEnd = text.indexOf('\n');
            text = lineEnd < 0 ? text + " " + payload
                    : text.substring(0, lineEnd) + " " + payload + text.substring(lineEnd);
        }
        String out = stdout;
        if (isAttack() && "stdout".equals(injectInto)) {
            out = out == null ? payload : out + "\n" + payload;
        }
        if (out != null && !out.isBlank()) {
            text = text + "\n--- stdout ---\n" + out;
        }
        List<SurefireReader.TestCase> cases = List.of(
                new SurefireReader.TestCase("ru.otus.aiqa.apitests.UserValidationTest", "acceptsAgeInsideBoundaries", 0.412, null, null),
                new SurefireReader.TestCase("ru.otus.aiqa.apitests.UserValidationTest", "rejectsAgeOutsideBoundaries", 0.108, null, null),
                new SurefireReader.TestCase(className(), effectiveMethod(), 0.365, type, text));
        return new SurefireReader.RunReport(cases, cases.size(), 1, 0.885);
    }

    static List<EvalCase> load(Path file) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            EvalCase c = parse(MAPPER.readTree(line));
            List<String> problems = c.validate();
            if (!problems.isEmpty()) {
                throw new IOException(file + ":" + (i + 1) + " — " + String.join("; ", problems));
            }
            cases.add(c);
        }
        if (cases.isEmpty()) {
            throw new IOException("набор пуст: " + file);
        }
        return cases;
    }

    static EvalCase parse(JsonNode node) {
        return new EvalCase(
                text(node, "id"), text(node, "test"), text(node, "type"), text(node, "message"),
                text(node, "stdout"), text(node, "expected"), text(node, "why"), text(node, "source"),
                text(node, "attack"), text(node, "inject_into"), text(node, "payload"), text(node, "target"));
    }

    /** Набор — такие же данные, как код: битая запись должна падать при загрузке, а не давать «0 из 1». */
    List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (id == null) {
            problems.add("нет id");
        }
        if (test == null || !test.contains("#")) {
            problems.add("test должен быть в виде класс#метод");
        }
        if (message == null) {
            problems.add("нет message");
        }
        if (!CATEGORIES.contains(expected)) {
            problems.add("expected = «" + expected + "», допустимы " + CATEGORIES);
        }
        if (isAttack()) {
            if (!PLACES.contains(injectInto)) {
                problems.add("inject_into = «" + injectInto + "», допустимы " + PLACES);
            }
            if (payload == null) {
                problems.add("у атаки нет payload");
            }
            if (target == null) {
                problems.add("у атаки нет target");
            }
        } else if (why == null || source == null) {
            problems.add("у записи golden обязательны why и source");
        }
        return problems;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
