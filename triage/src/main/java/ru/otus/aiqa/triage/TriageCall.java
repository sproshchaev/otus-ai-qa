package ru.otus.aiqa.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сердце LLM-шага без обвязки командной строки: промпт → кэш или модель → контракт → защита выхода.
 * Вынесено из TriageApplication к занятию 29, чтобы прогон по наборам данных (модуль evals)
 * проверял ровно тот код, который работает в конвейере, а не его копию.
 */
public final class TriageCall {

    /** Модель как функция «промпт → сырой ответ». В тестах вместо неё подставляется заглушка. */
    @FunctionalInterface
    public interface Model {
        String generate(String prompt) throws Exception;
    }

    /** Ответ, прошедший контракт: разобранный JSON, сырой текст и был ли repair-запрос. */
    public record Verdict(JsonNode json, String raw, boolean repaired) {
    }

    /** Граница блока данных в промпте. Совпадает с разделителями в prompts/triage.md с версии 3. */
    static final String DATA_TAG = "данные_отчёта";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Model model;
    private final String modelName;
    private final Path cacheDir;

    /** cacheDir = null — без кэша: каждый вызов идёт в модель. */
    public TriageCall(Model model, String modelName, Path cacheDir) {
        this.model = model;
        this.modelName = modelName;
        this.cacheDir = cacheDir;
    }

    /** Боевая конфигурация: Ollama, temperature 0, seed 42 — см. OllamaClient. */
    public static TriageCall ollama(String baseUrl, String modelName, Path cacheDir) {
        OllamaClient client = new OllamaClient(baseUrl, modelName, Duration.ofSeconds(90));
        return new TriageCall(prompt -> client.generateWithRetry(prompt, true), modelName, cacheDir);
    }

    /** Подстановка прогона в шаблон промпта. Текст падений проходит санитизацию. */
    public static String renderPrompt(String template, SurefireReader.RunReport report) {
        String cases = report.cases().stream()
                .map(c -> "- %s#%s | %.3f c | %s".formatted(
                        c.className(), c.name(), c.timeSeconds(),
                        c.failed()
                                ? c.failureType() + ": " + Sanitizer.mask(c.failureMessage())
                                : "passed"))
                .collect(Collectors.joining("\n"));
        return stripComments(template)
                .replace("{{TOTAL}}", String.valueOf(report.total()))
                .replace("{{FAILED}}", String.valueOf(report.failed()))
                .replace("{{DURATION}}", "%.1f".formatted(report.totalSeconds()))
                .replace("{{CASES}}", neutralizeDataTags(cases));
    }

    /**
     * HTML-комментарии шаблона — журнал версий для людей, модели он не адресован. Найдено 13.10.2026:
     * журнал v3 с фразами «категория менялась на flaky, env» уходил в модель вместе с промптом,
     * и она перестала ставить flaky и env вовсе — golden упал с 11 до 8 из 13.
     */
    static String stripComments(String template) {
        return template.replaceAll("(?s)<!--.*?-->\\s*", "");
    }

    /**
     * Данные не имеют права закрыть блок данных сами: иначе текст после «закрывающего тега»
     * в сообщении ассерта модель читает уже как продолжение инструкций.
     */
    static String neutralizeDataTags(String text) {
        return text.replace("<" + DATA_TAG + ">", "[" + DATA_TAG + "]")
                .replace("</" + DATA_TAG + ">", "[/" + DATA_TAG + "]");
    }

    /** Вызов без проверки на утечку секретов: контракт и согласованность с прогоном. */
    public Verdict ask(String prompt, SurefireReader.RunReport report) throws Exception {
        return ask(prompt, report, List.of(), true);
    }

    /**
     * Полный вызов. guard = false выключает проверку выхода — только для прогона «до защиты»
     * в evals, в конвейере защита включена всегда.
     */
    public Verdict ask(String prompt, SurefireReader.RunReport report,
                       Collection<String> secrets, boolean guard) throws Exception {
        String raw = fromCacheOrModel(prompt);
        Verdict verdict = parseOrRepair(raw, prompt);
        if (guard) {
            List<String> problems = OutputGuard.check(verdict.raw(), verdict.json(), report, secrets);
            if (!problems.isEmpty()) {
                throw new OutputGuard.Rejected(problems);
            }
        }
        return verdict;
    }

    /** Кэш по хешу «промпт + модель»: одинаковый вход — тот же ответ, ноль вызовов и токенов. */
    private String fromCacheOrModel(String prompt) throws Exception {
        if (cacheDir == null) {
            return model.generate(prompt);
        }
        String key = sha256(modelName + "\n" + prompt);
        Path cached = cacheDir.resolve(key + ".json");
        if (Files.exists(cached)) {
            System.err.println("[triage] ответ взят из кэша: " + cached);
            return Files.readString(cached);
        }
        String raw = model.generate(prompt);
        Files.createDirectories(cacheDir);
        Files.writeString(cached, raw, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return raw;
    }

    /** Не прошло контракт — один repair-запрос, и только потом сдаёмся. */
    private Verdict parseOrRepair(String raw, String prompt) throws Exception {
        JsonNode parsed = tryParse(raw);
        List<String> problems = ResponseValidator.validate(parsed);
        if (problems.isEmpty()) {
            return new Verdict(parsed, raw, false);
        }
        System.err.println("[triage] ответ не прошёл контракт: " + problems);
        String repair = prompt + "\n\nПредыдущий ответ нарушил контракт: "
                + String.join("; ", problems) + ". Верни только корректный JSON по схеме.";
        String secondRaw = model.generate(repair);
        JsonNode second = tryParse(secondRaw);
        List<String> stillBad = ResponseValidator.validate(second);
        if (!stillBad.isEmpty()) {
            throw new IllegalStateException("ответ модели не соответствует схеме: " + stillBad);
        }
        return new Verdict(second, secondRaw, true);
    }

    private static JsonNode tryParse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
