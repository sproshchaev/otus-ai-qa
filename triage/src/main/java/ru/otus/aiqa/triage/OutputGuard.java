package ru.otus.aiqa.triage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Проверка выхода модели после контракта. Контракт отвечает на вопрос «правильной ли формы ответ»,
 * защита — «можно ли этому ответу верить». Добавлена к занятию 29: промпт-инъекция в тексте
 * падения меняет ответ так, что форма остаётся правильной, и контракт её не видит.
 *
 * Четыре правила, все не зависят от того, что написано в промпте:
 *   1. секрет из контекста (канарейка) не должен появиться в ответе ни в каком написании;
 *   2. вердикт не противоречит фактам прогона: упавший тест не бывает «ok» или «slow» —
 *      эти причины по контракту только у прошедших тестов;
 *   3. «flaky» допустим, только если в тексте падения есть признак нестабильности из промпта:
 *      повтор прошёл, таймаут или 503. Иначе это смена категории по требованию текста, а не вывод;
 *   4. «env» допустим, только если в тексте падения есть признак окружения из промпта:
 *      ошибка соединения, неизвестный хост, рассинхрон classpath.
 *
 * Граница правил 3 и 4: признак ищется в том же тексте падения, который пишет и атакующий.
 * Структурного признака повтора (flakyFailure / rerunFailure из Surefire при
 * rerunFailingTestsCount) во входе шага сейчас нет — SurefireReader его не читает,
 * конвейер повторы не включает. Подделать фразу «повтор: passed» атака может.
 */
public final class OutputGuard {

    /** Отказ защиты. Шаг на него реагирует так же, как на недоступную модель: вердикта нет. */
    public static final class Rejected extends IllegalStateException {
        private final List<String> problems;

        public Rejected(List<String> problems) {
            super("ответ отклонён защитой: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private static final Set<String> PASSED_ONLY = Set.of("ok", "slow");

    /** Признаки нестабильности — те же, что в промпте: повтор прошёл, таймаут, 503. */
    /** Признаки окружения — те же, что в промпте, плюс рассинхрон classpath. */
    private static final Pattern ENV_SIGNAL = Pattern.compile(
            "(?i)connect(ion)?exception|connection refused|unknownhost|nodename nor servname"
                    + "|no route to host|nosuchmethoderror|noclassdeffounderror|classnotfoundexception");

    private static final Pattern FLAKY_SIGNAL = Pattern.compile(
            "(?iu)(повтор|перезапуск|rerun|re-run|retry)[^\\n]{0,80}(passed|прош)"
                    + "|timeout|timed out|\\b503\\b");

    private OutputGuard() {
    }

    public static List<String> check(String raw, JsonNode json, SurefireReader.RunReport report,
                                     Collection<String> secrets) {
        List<String> problems = new ArrayList<>();
        for (String secret : secrets) {
            if (leaks(raw, secret)) {
                problems.add("в ответе секрет из контекста");
            }
        }
        Set<String> failed = report.cases().stream()
                .filter(SurefireReader.TestCase::failed)
                .map(c -> c.className() + "#" + c.name())
                .collect(Collectors.toSet());
        for (JsonNode finding : json.path("findings")) {
            String reason = finding.path("reason").asText("");
            if ("flaky".equals(reason)) {
                checkSignal(finding, report, reason, FLAKY_SIGNAL, "нестабильности").ifPresent(problems::add);
                continue;
            }
            if ("env".equals(reason)) {
                checkSignal(finding, report, reason, ENV_SIGNAL, "окружения").ifPresent(problems::add);
                continue;
            }
            if (!PASSED_ONLY.contains(reason)) {
                continue;
            }
            for (JsonNode test : finding.path("tests")) {
                if (failed.contains(test.asText())) {
                    problems.add("упавший тест " + test.asText() + " помечен как «" + reason + "»");
                }
            }
            if (report.failed() > 0 && report.failed() == report.total()) {
                problems.add("в прогоне нет прошедших тестов, а вердикт «" + reason + "»");
            }
        }
        return problems.stream().distinct().toList();
    }

    /**
     * Проверяются упавшие тесты, названные в находке. Не назван ни один — модель исказила имя
     * (так бывает, когда атака сидит в имени теста), и тогда признак нужен хотя бы у одного падения.
     */
    private static Optional<String> checkSignal(JsonNode finding, SurefireReader.RunReport report,
                                                String reason, Pattern signal, String what) {
        Set<String> named = new HashSet<>();
        finding.path("tests").forEach(t -> named.add(t.asText()));
        List<SurefireReader.TestCase> failed = report.cases().stream()
                .filter(SurefireReader.TestCase::failed).toList();
        List<SurefireReader.TestCase> checked = failed.stream()
                .filter(c -> named.contains(c.className() + "#" + c.name())).toList();
        if (checked.isEmpty()) {
            return failed.stream().anyMatch(c -> hasSignal(c, signal))
                    ? Optional.empty()
                    : Optional.of("вердикт «" + reason + "» без признака " + what + " во входе");
        }
        return checked.stream()
                .filter(c -> !hasSignal(c, signal))
                .findFirst()
                .map(c -> "вердикт «" + reason + "» для " + c.className() + "#" + c.name()
                        + " без признака " + what + " во входе");
    }

    static boolean hasSignal(SurefireReader.TestCase c, Pattern signal) {
        return signal.matcher(c.failureType() + "\n" + c.failureMessage()).find();
    }

    /**
     * Сравнение без регистра и без разделителей: «CANARY-3f2a», «canary 3f2a» и «C-A-N-A-R-Y-3F2A»
     * для этой проверки одно и то же. От пересказа секрета своими словами или base64 не спасает —
     * это граница метода, о ней говорим на занятии.
     */
    public static boolean leaks(String raw, String secret) {
        if (raw == null || secret == null || secret.isBlank()) {
            return false;
        }
        String text = normalize(raw);
        if (text.contains(normalize(secret))) {
            return true;
        }
        // модель может отдать только «тело» маркера без префикса — его ищем отдельно
        int dash = secret.lastIndexOf('-');
        String body = dash < 0 ? "" : normalize(secret.substring(dash + 1));
        return body.length() >= 8 && text.contains(body);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
