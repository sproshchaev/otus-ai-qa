package ru.otus.aiqa.triage;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM-шаг конвейера. Два режима:
 *   --input  <путь>  разбор отчёта Surefire (директория или файл) → markdown-отчёт
 *   --generate <файл промпта>  черновик конвейера GitHub Actions → stdout
 *
 * Шаг устроен так, чтобы никогда не ронять сборку: любая ошибка — предупреждение и выход 0.
 */
public final class TriageApplication {

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
        String prompt = TriageCall.renderPrompt(Files.readString(PROMPT), report);

        Path artifacts = OUT_DIR;
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("triage-prompt.txt"), prompt);

        TriageCall.Verdict verdict = TriageCall.ollama(OLLAMA_URL, MODEL, CACHE_DIR)
                .ask(prompt, report);
        Files.writeString(artifacts.resolve("triage-response.json"), verdict.raw());
        emit(render(verdict.json(), report));
    }

    private static void generate(Path promptFile) throws Exception {
        String prompt = Files.readString(promptFile);
        String raw = new OllamaClient(OLLAMA_URL, MODEL, Duration.ofSeconds(120))
                .generateWithRetry(prompt, false);
        System.out.println(stripCodeFence(raw));
    }

    /**
     * Модель охотно оборачивает YAML в ```yml … ``` вопреки инструкции «только YAML».
     * Ограждение снимаем сами: линтеры на таком файле спотыкаются о первую же строку.
     * Проверено 26.08.2026 на llama3.1:8b — actionlint падал с «could not parse as YAML».
     */
    static String stripCodeFence(String text) {
        String body = text.strip();
        if (!body.startsWith("```")) {
            return body;
        }
        int firstLineEnd = body.indexOf('\n');
        if (firstLineEnd < 0) {
            return body;
        }
        body = body.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        return (closing < 0 ? body : body.substring(0, closing)).strip();
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
            String tests = formatTests(finding.path("tests"));
            md.append("| `").append(finding.path("reason").asText()).append("` | ")
                    .append(tests).append(" | ")
                    .append(finding.path("hypothesis").asText()).append(" | ")
                    .append("%.2f".formatted(finding.path("confidence").asDouble())).append(" |\n");
        }
        md.append("\n_Шаг advisory: он ничего не блокирует и не меняет статус сборки._\n");
        return md.toString();
    }

    /**
     * Список тестов в отчёте обрезаем: на зелёном прогоне модель охотно перечисляет все два десятка,
     * и таблица становится нечитаемой. Проверено 26.08.2026 на llama3.1:8b.
     */
    private static String formatTests(JsonNode tests) {
        List<String> names = new ArrayList<>();
        tests.forEach(node -> names.add(node.asText()));
        if (names.isEmpty()) {
            return "—";
        }
        if (names.size() <= 3) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + " и ещё " + (names.size() - 3);
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
