package ru.otus.aiqa.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Контракт шага ревью: ответ модели — объект известной формы, а не текст.
 * Проверяем его до того, как он уйдёт в комментарий к PR: комментарий читают люди,
 * и «модель что-то ответила» там не годится.
 */
final class ReviewValidator {

    static final Set<String> SEVERITIES = Set.of("blocker", "major", "minor", "info");
    static final Set<String> CATEGORIES = Set.of("antipattern", "maintenance", "coverage", "readability");

    private ReviewValidator() {
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
        if (!findings.isArray()) {
            problems.add("поле findings должно быть массивом");
            return problems;
        }
        for (int i = 0; i < findings.size(); i++) {
            JsonNode f = findings.get(i);
            String prefix = "findings[" + i + "]";

            String severity = f.path("severity").asText("");
            if (!SEVERITIES.contains(severity)) {
                problems.add(prefix + ".severity = «" + severity + "», допустимы " + SEVERITIES);
            }
            String category = f.path("category").asText("");
            if (!CATEGORIES.contains(category)) {
                problems.add(prefix + ".category = «" + category + "», допустимы " + CATEGORIES);
            }
            if (!f.path("file").isTextual() || f.path("file").asText().isBlank()) {
                problems.add(prefix + ".file должен быть непустой строкой");
            }
            if (!f.path("line").isInt() || f.path("line").asInt() < 1) {
                problems.add(prefix + ".line должен быть номером строки, начиная с 1");
            }
            if (!f.path("finding").isTextual() || f.path("finding").asText().isBlank()) {
                problems.add(prefix + ".finding должен быть непустой строкой");
            }
            // ревью без предложения, что делать, — это жалоба, а не ревью
            if (!f.path("suggestion").isTextual() || f.path("suggestion").asText().isBlank()) {
                problems.add(prefix + ".suggestion должен быть непустой строкой");
            }
            double confidence = f.path("confidence").asDouble(-1);
            if (confidence < 0 || confidence > 1) {
                problems.add(prefix + ".confidence должен быть числом от 0 до 1");
            }
        }
        return problems;
    }
}
