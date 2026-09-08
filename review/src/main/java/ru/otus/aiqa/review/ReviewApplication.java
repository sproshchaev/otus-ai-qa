package ru.otus.aiqa.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Шаг ревью автотестов. Порядок внутри шага тот же, что у человека на ревью:
 * сначала дешёвая механическая проверка правилами, потом разбор смысла моделью.
 *
 *   --path <путь>            каталог или файл тестов (по умолчанию api-tests/src/test/java)
 *   --changed <файл-списка>  список изменённых файлов, как его отдаёт git diff --name-only
 *   --rules-only             без модели: только правила, ноль вызовов и ноль секунд
 *
 * Шаг advisory: он не роняет сборку. Гейт включается переменной REVIEW_FAIL_ON —
 * решение это процессное, а не техническое, и принимается командой, а не инструментом.
 */
public final class ReviewApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OLLAMA_URL = env("OLLAMA_URL", "http://localhost:11434");
    private static final String MODEL = env("OLLAMA_MODEL", "llama3.1:8b");
    private static final Path CACHE_DIR = Path.of(env("REVIEW_CACHE", ".review-cache"));
    private static final boolean ENABLED = Boolean.parseBoolean(env("REVIEW_ENABLED", "true"));
    private static final Path PROMPT = Path.of(env("REVIEW_PROMPT", "prompts/review.md"));
    private static final Path OUT_DIR = Path.of(env("REVIEW_OUT", "review/target"));
    private static final int MAX_FILES = Integer.parseInt(env("REVIEW_MAX_FILES", "5"));
    private static final String FAIL_ON = env("REVIEW_FAIL_ON", "none");

    /** Порядок важности: по нему сортируется отчёт и по нему же решается судьба гейта. */
    private static final Map<String, Integer> RANK = Map.of("blocker", 0, "major", 1, "minor", 2, "info", 3);

    public static void main(String[] args) {
        try {
            List<TestSources.TestFile> files = collect(args);
            if (files.isEmpty()) {
                emit("### Ревью автотестов\n\nИзменённых файлов тестов нет — шаг пропущен.");
                return;
            }

            boolean rulesOnly = !ENABLED || has(args, "--rules-only");
            StringBuilder report = new StringBuilder();
            report.append("### Ревью автотестов\n\n");
            report.append("Файлов на ревью: **").append(files.size()).append("**");
            report.append(rulesOnly ? " · режим: только правила\n\n" : " · модель: `" + MODEL + "`\n\n");

            int blockers = 0;
            int majors = 0;
            for (TestSources.TestFile file : files) {
                List<SmellDetector.Smell> smells = SmellDetector.scan(file.source());
                report.append(renderRules(file, smells));
                blockers += count(smells, "blocker");
                majors += count(smells, "major");

                if (rulesOnly) {
                    continue;
                }
                try {
                    JsonNode verdict = filter(ask(file, smells), smells, file);
                    report.append(renderModel(verdict));
                    blockers += countModel(verdict, "blocker");
                    majors += countModel(verdict, "major");
                } catch (Exception e) {
                    // fail-soft на уровне файла: один недоступный вызов не отменяет ревью остальных
                    report.append("_Модель не ответила по файлу `").append(file.path())
                            .append("`: ").append(describe(e)).append("_\n\n");
                    System.err.println("[review] " + file.path() + ": " + e);
                }
            }

            report.append(footer(blockers, majors));
            emit(report.toString());
            gate(blockers, majors);
        } catch (Exception e) {
            emit("### Ревью автотестов пропущено\n\nПричина: " + describe(e)
                    + "\n\nСборка на это не влияет: шаг advisory.");
            System.err.println("[review] " + e);
        }
    }

    private static List<TestSources.TestFile> collect(String[] args) throws Exception {
        String changed = value(args, "--changed");
        if (changed != null) {
            return TestSources.fromChangedList(Path.of(changed), MAX_FILES);
        }
        String path = value(args, "--path");
        return TestSources.fromDirectory(Path.of(path == null ? "api-tests/src/test/java" : path), MAX_FILES);
    }

    /** Один вызов на файл: короткий вход даёт точные номера строк и не упирается в контекст модели. */
    private static JsonNode ask(TestSources.TestFile file, List<SmellDetector.Smell> smells) throws Exception {
        if (!Files.isReadable(PROMPT)) {
            throw new IllegalStateException("не найден промпт " + PROMPT.toAbsolutePath()
                    + " — запускать из корня репозитория или задать REVIEW_PROMPT");
        }
        String prompt = render(Files.readString(PROMPT), file, smells);

        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve("review-prompt.txt"), prompt);

        String raw = fromCacheOrModel(prompt);
        return parseOrRepair(raw, prompt);
    }

    private static String render(String template, TestSources.TestFile file, List<SmellDetector.Smell> smells) {
        String numbered = numberLines(Sanitizer.mask(file.source()));
        String rules = smells.isEmpty()
                ? "правила не нашли ничего"
                : smells.stream()
                        .map(s -> "- строка %d · %s · %s".formatted(s.line(), s.rule(), s.message()))
                        .collect(Collectors.joining("\n"));
        return template
                .replace("{{FILE}}", file.path().toString())
                .replace("{{LINES}}", String.valueOf(file.lines()))
                .replace("{{RULES}}", rules)
                .replace("{{SOURCE}}", numbered);
    }

    /** Номера строк проставляем сами: без них модель называет строку наугад, и находку не проверить. */
    private static String numberLines(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Кэш по хешу «промпт + модель»: тот же файл на повторном прогоне не стоит ни секунды. */
    private static String fromCacheOrModel(String prompt) throws Exception {
        String key = sha256(MODEL + "\n" + prompt);
        Path cached = CACHE_DIR.resolve(key + ".json");
        if (Files.exists(cached)) {
            System.err.println("[review] ответ взят из кэша: " + cached);
            return Files.readString(cached);
        }
        String raw = new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(120))
                .generateWithRetry(prompt, true);
        Files.createDirectories(CACHE_DIR);
        Files.writeString(cached, raw, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return raw;
    }

    /** Не прошло контракт — один repair-запрос, и только потом сдаёмся. */
    private static JsonNode parseOrRepair(String raw, String prompt) throws Exception {
        JsonNode parsed = tryParse(raw);
        List<String> problems = ReviewValidator.validate(parsed);
        if (problems.isEmpty()) {
            return parsed;
        }
        System.err.println("[review] ответ не прошёл контракт: " + problems);
        String repair = prompt + "\n\nПредыдущий ответ нарушил контракт: "
                + String.join("; ", problems) + ". Верни только корректный JSON по схеме.";
        JsonNode second = tryParse(new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(120))
                .generateWithRetry(repair, true));
        List<String> stillBad = ReviewValidator.validate(second);
        if (!stillBad.isEmpty()) {
            throw new IllegalStateException("ответ модели не соответствует схеме: " + stillBad);
        }
        return second;
    }

    /**
     * Форму ответа проверил контракт, правдоподобие проверяем здесь.
     * Отсев печатаем в stderr: молчаливая фильтрация прячет то, как ведёт себя модель.
     */
    private static JsonNode filter(JsonNode verdict, List<SmellDetector.Smell> smells,
                                   TestSources.TestFile file) {
        FindingFilter.Result result = FindingFilter.apply(
                verdict, smells, file.lines(), file.source().split("\n", -1));
        for (String reason : result.dropped()) {
            System.err.println("[review] находка отброшена: " + reason);
        }
        return FindingFilter.rebuild(verdict, result.kept());
    }

    private static JsonNode tryParse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String renderRules(TestSources.TestFile file, List<SmellDetector.Smell> smells) {
        StringBuilder md = new StringBuilder();
        md.append("#### `").append(file.path()).append("`\n\n");
        md.append("**Правила** (").append(smells.size()).append("):\n\n");
        if (smells.isEmpty()) {
            md.append("_чисто — на этом файле правила не срабатывают_\n\n");
            return md.toString();
        }
        md.append("| Строка | Правило | Важность | Что не так |\n|---|---|---|---|\n");
        smells.stream()
                .sorted((a, b) -> Integer.compare(RANK.getOrDefault(a.severity(), 9),
                        RANK.getOrDefault(b.severity(), 9)))
                .forEach(s -> md.append("| ").append(s.line())
                        .append(" | `").append(s.rule())
                        .append("` | ").append(s.severity())
                        .append(" | ").append(s.message()).append(" |\n"));
        md.append('\n');
        return md.toString();
    }

    private static String renderModel(JsonNode verdict) {
        StringBuilder md = new StringBuilder();
        md.append("**Модель**: ").append(verdict.path("summary").asText()).append("\n\n");
        JsonNode findings = verdict.path("findings");
        if (!findings.isArray() || findings.isEmpty()) {
            md.append("_замечаний сверх правил модель не нашла_\n\n");
            return md.toString();
        }
        md.append("| Строка | Важность | Категория | Замечание | Что сделать | Увер. |\n");
        md.append("|---|---|---|---|---|---|\n");
        List<JsonNode> sorted = new ArrayList<>();
        findings.forEach(sorted::add);
        sorted.sort((a, b) -> Integer.compare(
                RANK.getOrDefault(a.path("severity").asText(), 9),
                RANK.getOrDefault(b.path("severity").asText(), 9)));
        for (JsonNode f : sorted) {
            md.append("| ").append(f.path("line").asInt())
                    .append(" | ").append(f.path("severity").asText())
                    .append(" | `").append(f.path("category").asText())
                    .append("` | ").append(f.path("finding").asText())
                    .append(" | ").append(f.path("suggestion").asText())
                    .append(" | ").append("%.2f".formatted(f.path("confidence").asDouble()))
                    .append(" |\n");
        }
        md.append('\n');
        return md.toString();
    }

    private static String footer(int blockers, int majors) {
        String verdict = "\n**Итого:** blocker — " + blockers + ", major — " + majors + ".\n\n";
        if ("none".equals(FAIL_ON)) {
            return verdict + "_Шаг advisory: он ничего не блокирует и не меняет статус сборки._\n";
        }
        return verdict + "_Шаг работает как гейт: REVIEW_FAIL_ON=" + FAIL_ON + "._\n";
    }

    /**
     * Гейт выключен по умолчанию. Включать его стоит только после того, как команда
     * посмотрела на находки хотя бы месяц: инструмент, который начинает с блокировки,
     * выключают в первую неделю.
     */
    private static void gate(int blockers, int majors) {
        boolean fail = switch (FAIL_ON) {
            case "blocker" -> blockers > 0;
            case "major" -> blockers > 0 || majors > 0;
            default -> false;
        };
        if (fail) {
            System.err.println("[review] порог REVIEW_FAIL_ON=" + FAIL_ON + " превышен");
            System.exit(1);
        }
    }

    private static int count(List<SmellDetector.Smell> smells, String severity) {
        return (int) smells.stream().filter(s -> s.severity().equals(severity)).count();
    }

    private static int countModel(JsonNode verdict, String severity) {
        int n = 0;
        for (JsonNode f : verdict.path("findings")) {
            if (severity.equals(f.path("severity").asText())) {
                n++;
            }
        }
        return n;
    }

    /** Результат уходит и в лог, и в сводку GitHub Actions, и в файл — его забирает шаг комментария в PR. */
    private static void emit(String markdown) {
        System.out.println(markdown);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) {
            try {
                Files.writeString(Path.of(summary), markdown + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                System.err.println("[review] не удалось записать summary: " + e.getMessage());
            }
        }
        try {
            Files.createDirectories(OUT_DIR);
            Files.writeString(OUT_DIR.resolve("review.md"), markdown);
        } catch (Exception e) {
            System.err.println("[review] не удалось сохранить отчёт: " + e.getMessage());
        }
    }

    /** У сетевых исключений message часто пуст — в отчёт должно попасть что-то осмысленное. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName() + " (модель недоступна по адресу " + OLLAMA_URL + ")"
                : message;
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String value(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
