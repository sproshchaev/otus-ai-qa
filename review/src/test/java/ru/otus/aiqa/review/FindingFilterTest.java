package ru.otus.aiqa.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Каждый случай здесь наблюдался на прогоне llama3.1:8b 08.09.2026,
 * а не придуман: фильтр закрывает поведение модели, а не гипотезу о нём.
 */
class FindingFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode verdict(String findings) throws Exception {
        return MAPPER.readTree("{\"summary\":\"итог\",\"findings\":[" + findings + "]}");
    }

    private static String finding(int line, String text) {
        return """
                {"file":"T.java","line":%d,"severity":"major","category":"coverage",
                 "finding":"%s","suggestion":"что сделать","confidence":0.8}
                """.formatted(line, text);
    }

    @Test
    @DisplayName("Находка проходит, когда номер строки в тексте совпадает с полем line")
    void keepsConsistentFinding() throws Exception {
        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(47, "Ассерт внутри if на строке 47")), List.of(), 100);

        assertEquals(1, result.kept().size(), "согласованная находка должна остаться");
        assertTrue(result.dropped().isEmpty(), "отбрасывать нечего");
    }

    @Test
    @DisplayName("Находка отбрасывается, когда текст называет одну строку, а поле line другую")
    void dropsContradictingLine() throws Exception {
        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(27, "ассерт внутри if на строке 46")), List.of(), 100);

        assertEquals(0, result.kept().size(), "противоречивую находку проверить нельзя");
        assertEquals(1, result.dropped().size(), "причина отсева должна быть названа");
        assertTrue(result.dropped().get(0).contains("46"), "в причине должна быть строка из текста");
    }

    @Test
    @DisplayName("Находка за пределами файла отбрасывается")
    void dropsLineBeyondFile() throws Exception {
        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(900, "замечание по существу")), List.of(), 84);

        assertEquals(0, result.kept().size(), "строки 900 в файле на 84 строки нет");
    }

    @Test
    @DisplayName("Повтор находки правил на той же строке отбрасывается")
    void dropsRepeatOfRule() throws Exception {
        List<SmellDetector.Smell> smells = List.of(
                new SmellDetector.Smell("hardcoded-url", 20, "minor", "Адрес стенда зашит в код"));

        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(20, "Адрес localhost зашит в тест")), smells, 100);

        assertEquals(0, result.kept().size(), "за механические дефекты отвечают правила");
        assertTrue(result.dropped().get(0).contains("повтор"), "причина должна называться повтором");
    }

    @Test
    @DisplayName("Содержательная находка на строке с находкой правил остаётся")
    void keepsDifferentFindingOnSameLine() throws Exception {
        List<SmellDetector.Smell> smells = List.of(
                new SmellDetector.Smell("assert-without-message", 47, "major", "Ассерт без сообщения"));

        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(47, "Проверка стоит внутри условия и может не выполниться")), smells, 100);

        assertEquals(1, result.kept().size(), "разные дефекты на одной строке не сливаются");
    }

    @Test
    @DisplayName("Пересборка сохраняет summary модели")
    void rebuildKeepsSummary() throws Exception {
        JsonNode source = verdict(finding(10, "замечание"));
        FindingFilter.Result result = FindingFilter.apply(source, List.of(), 100);

        assertEquals("итог", FindingFilter.rebuild(source, result.kept()).path("summary").asText(),
                "summary не должен теряться при фильтрации");
    }

    @Test
    @DisplayName("Находка с кодом, которого нет у названной строки, отбрасывается")
    void dropsFindingNotConfirmedByFile() throws Exception {
        String[] source = ("class T {\n"
                + "    void a() {\n"
                + "        assertEquals(201, r.statusCode());\n"
                + "    }\n"
                + "}\n").split("\n", -1);

        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(3, "Ассерт стоит внутри if (found.statusCode() == 200)")),
                List.of(), source.length, source);

        assertEquals(0, result.kept().size(), "условия if рядом со строкой 3 нет");
        assertTrue(result.dropped().get(0).contains("примера из промпта"),
                "причина должна указывать на перенос примера");
    }

    @Test
    @DisplayName("Находка с кодом, который у названной строки есть, остаётся")
    void keepsFindingConfirmedByFile() throws Exception {
        String[] source = ("class T {\n"
                + "    void a() {\n"
                + "        if (found.statusCode() == 200) {\n"
                + "            assertEquals(1, found.total());\n"
                + "        }\n"
                + "    }\n"
                + "}\n").split("\n", -1);

        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(3, "Ассерт стоит внутри if (found.statusCode() == 200)")),
                List.of(), source.length, source);

        assertEquals(1, result.kept().size(), "находка подтверждается файлом");
    }

    @Test
    @DisplayName("Находка без кода в тексте правилом подтверждения не трогается")
    void keepsFindingWithoutCode() throws Exception {
        String[] source = "class T {\n}\n".split("\n", -1);

        FindingFilter.Result result = FindingFilter.apply(
                verdict(finding(1, "Негативный случай в наборе не проверяется вовсе")),
                List.of(), source.length, source);

        assertEquals(1, result.kept().size(), "проверять такую находку по коду нечем");
    }
}
