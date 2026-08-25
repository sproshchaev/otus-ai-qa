package ru.otus.aiqa.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM-шаг конвейера. Два режима:
 *   --input  <путь>  разбор отчёта Surefire (директория или файл) → markdown-отчёт
 *   --generate <файл промпта>  черновик конвейера GitHub Actions → stdout
 *
 * Шаг устроен так, чтобы никогда не ронять сборку: любая ошибка — предупреждение и выход 0.
 */
public final class TriageApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OLLAMA_URL = env("OLLAMA_URL", "http://localhost:11434");
    private static final String MODEL = env("OLLAMA_MODEL", "llama3.1:8b");
    private static final Path CACHE_DIR = Path.of(env("TRIAGE_CACHE", ".triage-cache"));
    private static final boolean ENABLED = Boolean.parseBoolean(env("TRIAGE_ENABLED", "true"));
    private static final Path PROMPT = Path.of(env("TRIAGE_PROMPT", "prompts/triage.md"));
    private static final Path OUT_DIR = Path.of(env("TRIAGE_OUT", "triage/target"));

    public static void main(String[] args) {
        try {
            if (!ENABLED) {
                emit("### LLM-шаг пропущен\n\nTRIAGE_ENABLED=false — шаг выключен переменной окружения.");
                return;
            }
            if (args.length >= 2 && args[0].equals("--generate")) {
                generate(Path.of(args[1]));
                return;
            }
            Path input = Path.of(args.length >= 2 && args[0].equals("--input")
                    ? args[1]
                    : "api-tests/target/surefire-reports");
            triage(input);
        } catch (Exception e) {
            // fail-soft: LLM не имеет права ронять чужую работу
            emit("### LLM-шаг пропущен\n\nПричина: " + describe(e)
                    + "\n\nСборка на это не влияет: шаг advisory.");
            System.err.println("[triage] " + e);
        }
    }

    private static void triage(Path input) throws Exception {
        SurefireReader.RunReport report = SurefireReader.read(input);
        if (!Files.isReadable(PROMPT)) {
            throw new IllegalStateException("не найден промпт " + PROMPT.toAbsolutePath()
                    + " — запускать из корня репозитория или задать TRIAGE_PROMPT");
        }
        String prompt = renderPrompt(Files.readString(PROMPT), report);

        Path artifacts = OUT_DIR;
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("triage-prompt.txt"), prompt);

        String raw = fromCacheOrModel(prompt);
        Files.writeString(artifacts.resolve("triage-response.json"), raw);

        JsonNode parsed = parseOrRepair(raw, prompt);
        emit(render(parsed, report));
    }

    private static void generate(Path promptFile) throws Exception {
        String prompt = Files.readString(promptFile);
        String raw = new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(120))
                .generateWithRetry(prompt, false);
        System.out.println(raw);
    }

    /** Кэш по хешу «промпт + модель»: одинаковый вход — тот же ответ, ноль вызовов и токенов. */
    private static String fromCacheOrModel(String prompt) throws Exception {
        String key = sha256(MODEL + "\n" + prompt);
        Path cached = CACHE_DIR.resolve(key + ".json");
        if (Files.exists(cached)) {
            System.err.println("[triage] ответ взят из кэша: " + cached);
            return Files.readString(cached);
        }
        String raw = new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(90))
                .generateWithRetry(prompt, true);
        Files.createDirectories(CACHE_DIR);
        Files.writeString(cached, raw, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return raw;
    }

    /** Не прошло контракт — один repair-запрос, и только потом сдаёмся. */
    private static JsonNode parseOrRepair(String raw, String prompt) throws Exception {
        JsonNode parsed = tryParse(raw);
        List<String> problems = ResponseValidator.validate(parsed);
        if (problems.isEmpty()) {
            return parsed;
        }
        System.err.println("[triage] ответ не прошёл контракт: " + problems);
        String repair = prompt + "\n\nПредыдущий ответ нарушил контракт: "
                + String.join("; ", problems) + ". Верни только корректный JSON по схеме.";
        JsonNode second = tryParse(new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(90))
                .generateWithRetry(repair, true));
        List<String> stillBad = ResponseValidator.validate(second);
        if (!stillBad.isEmpty()) {
            throw new IllegalStateException("ответ модели не соответствует схеме: " + stillBad);
        }
        return second;
    }

    private static JsonNode tryParse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String renderPrompt(String template, SurefireReader.RunReport report) {
        String cases = report.cases().stream()
                .map(c -> "- %s#%s | %.3f c | %s".formatted(
                        c.className(), c.name(), c.timeSeconds(),
                        c.failed()
                                ? c.failureType() + ": " + Sanitizer.mask(c.failureMessage())
                                : "passed"))
                .collect(Collectors.joining("\n"));
        return template
                .replace("{{TOTAL}}", String.valueOf(report.total()))
                .replace("{{FAILED}}", String.valueOf(report.failed()))
                .replace("{{DURATION}}", "%.1f".formatted(report.totalSeconds()))
                .replace("{{CASES}}", cases);
    }

    private static String render(JsonNode result, SurefireReader.RunReport report) {
        StringBuilder md = new StringBuilder();
        md.append("### Разбор прогона тестов моделью `").append(MODEL).append("`\n\n");
        md.append("Тестов: **").append(report.total()).append("**, упало: **")
                .append(report.failed()).append("**, длительность: **")
                .append("%.1f".formatted(report.totalSeconds())).append(" c**\n\n");
        md.append(result.path("summary").asText()).append("\n\n");
        md.append("| Причина | Тесты | Гипотеза | Уверенность |\n|---|---|---|---|\n");
        for (JsonNode finding : result.path("findings")) {
            String tests = String.join(", ", MAPPER.convertValue(finding.path("tests"), List.class)
                    .stream().map(String::valueOf).toList());
            md.append("| `").append(finding.path("reason").asText()).append("` | ")
                    .append(tests).append(" | ")
                    .append(finding.path("hypothesis").asText()).append(" | ")
                    .append("%.2f".formatted(finding.path("confidence").asDouble())).append(" |\n");
        }
        md.append("\n_Шаг advisory: он ничего не блокирует и не меняет статус сборки._\n");
        return md.toString();
    }

    /** Результат уходит и в лог, и в сводку GitHub Actions, если она доступна. */
    private static void emit(String markdown) {
        System.out.println(markdown);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) {
            try {
                Files.writeString(Path.of(summary), markdown + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                System.err.println("[triage] не удалось записать summary: " + e.getMessage());
            }
        }
        try {
            Files.createDirectories(OUT_DIR);
            Files.writeString(OUT_DIR.resolve("triage.md"), markdown);
        } catch (Exception e) {
            System.err.println("[triage] не удалось сохранить отчёт: " + e.getMessage());
        }
    }

    /** У сетевых исключений message часто пуст — в отчёт должно попасть что-то осмысленное. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName() + " (модель недоступна по адресу " + OLLAMA_URL + ")"
                : message;
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
