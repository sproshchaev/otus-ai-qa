package ru.otus.aiqa.triage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Контракт шага: ответ модели — не текст, а объект известной формы.
 * Проверяем его до использования, иначе в отчёт уедет что угодно.
 */
final class ResponseValidator {

    private static final Set<String> ALLOWED_REASONS = Set.of("ok", "bug", "flaky", "env", "data", "slow");

    private ResponseValidator() {
    }

    static List<String> validate(JsonNode root) {
        List<String> problems = new ArrayList<>();
        if (root == null || !root.isObject()) {
            problems.add("ответ не является объектом JSON");
            return problems;
        }
        if (!root.path("summary").isTextual() || root.path("summary").asText().isBlank()) {
            problems.add("поле summary отсутствует или пустое");
        }
        JsonNode findings = root.path("findings");
        if (!findings.isArray() || findings.isEmpty()) {
            problems.add("поле findings должно быть непустым массивом");
            return problems;
        }
        for (int i = 0; i < findings.size(); i++) {
            JsonNode finding = findings.get(i);
            String prefix = "findings[" + i + "]";
            String reason = finding.path("reason").asText("");
            if (!ALLOWED_REASONS.contains(reason)) {
                problems.add(prefix + ".reason = «" + reason + "», допустимы " + ALLOWED_REASONS);
            }
            if (!finding.path("tests").isArray()) {
                problems.add(prefix + ".tests должен быть массивом");
            }
            JsonNode hypothesis = finding.path("hypothesis");
            if (!hypothesis.isTextual() || hypothesis.asText().isBlank()) {
                problems.add(prefix + ".hypothesis должен быть непустой строкой");
            }
            double confidence = finding.path("confidence").asDouble(-1);
            if (confidence < 0 || confidence > 1) {
                problems.add(prefix + ".confidence должен быть числом от 0 до 1");
            }
        }
        return problems;
    }
}
