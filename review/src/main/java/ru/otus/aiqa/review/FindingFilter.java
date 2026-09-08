package ru.otus.aiqa.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Контракт проверяет форму ответа, но не его правдоподобие.
 * Здесь отсеиваются находки, которые форму прошли, а проверку по файлу не проходят.
 *
 * Оба правила выведены из прогонов 08.09.2026 на llama3.1:8b, а не из общих соображений:
 *  · модель называет в тексте одну строку, а в поле line ставит другую — проверить такую
 *    находку нельзя, а читатель верит первой попавшейся цифре;
 *  · модель повторяет находку правил другими словами, хотя промпт это запрещает, —
 *    за механические дефекты уже отвечает SmellDetector, и дважды их показывать незачем;
 *  · модель переносит в ответ пример из промпта дословно — вместе с условием, которого
 *    в разбираемом файле нет. Лечится сверкой процитированного условия с самим файлом.
 */
final class FindingFilter {

    private static final Pattern LINE_IN_TEXT = Pattern.compile("строк[аеиу]?\\s+(\\d{1,5})");
    /** Условие, процитированное в тексте находки: его и сверяем с файлом целиком. */
    private static final Pattern CONDITION = Pattern.compile("\\b(?:if|while|for)\\s*\\(");

    /** Что и почему выброшено — уходит в stderr, чтобы отсев был виден, а не молчалив. */
    record Result(ArrayNode kept, List<String> dropped) {
    }

    /** Модель ошибается на пару строк даже когда права по существу — окно это прощает. */
    private static final int WINDOW = 3;

    private FindingFilter() {
    }

    static Result apply(JsonNode verdict, List<SmellDetector.Smell> smells, int fileLines) {
        return apply(verdict, smells, fileLines, null);
    }

    static Result apply(JsonNode verdict, List<SmellDetector.Smell> smells, int fileLines, String[] sourceLines) {
        ArrayNode kept = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        List<String> dropped = new ArrayList<>();

        for (JsonNode finding : verdict.path("findings")) {
            int line = finding.path("line").asInt();
            String text = finding.path("finding").asText("");

            if (line > fileLines) {
                dropped.add("строка " + line + " за пределами файла (" + fileLines + " строк)");
                continue;
            }
            Integer mentioned = lineMentionedIn(text);
            if (mentioned != null && mentioned != line) {
                dropped.add("в тексте названа строка " + mentioned + ", в поле line — " + line);
                continue;
            }
            if (repeatsRule(text, smells, line)) {
                dropped.add("повтор находки правил на строке " + line);
                continue;
            }
            if (sourceLines != null && !confirmedByFile(text, line, sourceLines)) {
                dropped.add("код из находки не найден рядом со строкой " + line
                        + " — похоже на перенос примера из промпта");
                continue;
            }
            kept.add(finding.deepCopy());
        }
        return new Result(kept, dropped);
    }

    /**
     * Находка, называющая фрагмент кода, обязана подтверждаться файлом: этот фрагмент
     * должен встречаться рядом с указанной строкой. Правило выведено из прогона 08.09.2026 —
     * модель переносила в ответ пример из промпта дословно, вместе с условием,
     * которого в разбираемом файле нет вовсе.
     * Находки без кода в тексте правило не трогает: их проверять нечем.
     */
    private static boolean confirmedByFile(String text, int line, String[] sourceLines) {
        String condition = quotedCondition(text);
        if (condition == null) {
            return true;   // находка без процитированного условия — проверять нечем
        }
        int from = Math.max(0, line - 1 - WINDOW);
        int to = Math.min(sourceLines.length, line + WINDOW);
        StringBuilder window = new StringBuilder();
        for (int i = from; i < to; i++) {
            window.append(sourceLines[i]);
        }
        return squeeze(window.toString()).contains(squeeze(condition));
    }

    /**
     * Тело условия из текста находки: «внутри if (found.statusCode() == 200)» → «found.statusCode() == 200».
     * Скобки считаем по балансу: внутри условия почти всегда есть вызов со своими скобками.
     */
    private static String quotedCondition(String text) {
        Matcher m = CONDITION.matcher(text);
        if (!m.find()) {
            return null;
        }
        int depth = 1;
        int start = m.end();
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    String body = text.substring(start, i).trim();
                    return body.isEmpty() ? null : body;
                }
            }
        }
        return null;   // цитата оборвалась — сверять нечего
    }

    /** Сравниваем без пробелов: перенос строки и отступы в файле не должны мешать совпадению. */
    private static String squeeze(String value) {
        return value.replaceAll("\\s+", "");
    }

    /** Номер строки, названный внутри текста находки; null — если в тексте его нет. */
    private static Integer lineMentionedIn(String text) {
        Matcher m = LINE_IN_TEXT.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * Повтором считаем совпадение по строке плюс общее ключевое слово с сообщением правила.
     * Только по строке сравнивать нельзя: на одной строке уживаются и механический дефект,
     * и содержательный — например ассерт без сообщения внутри условия.
     */
    private static boolean repeatsRule(String text, List<SmellDetector.Smell> smells, int line) {
        String lower = text.toLowerCase();
        for (SmellDetector.Smell smell : smells) {
            if (smell.line() != line) {
                continue;
            }
            if (lower.contains(smell.rule()) || sharesKeyword(lower, smell.rule())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesKeyword(String lowerText, String rule) {
        return switch (rule) {
            case "sleep" -> lowerText.contains("sleep");
            case "order-dependence" -> lowerText.contains("порядок") || lowerText.contains("@order");
            case "hardcoded-url" -> lowerText.contains("localhost") || lowerText.contains("адрес");
            case "swallowed-exception" -> lowerText.contains("пустой catch") || lowerText.contains("catch");
            case "meaningless-name" -> lowerText.contains("имя теста") || lowerText.contains("название теста");
            case "assert-without-message" -> lowerText.contains("без сообщения");
            case "shared-mutable-state" -> lowerText.contains("статическ");
            case "copy-paste" -> lowerText.contains("копипаст") || lowerText.contains("дублиру")
                    || lowerText.contains("повторя");
            default -> false;
        };
    }

    /** Итоговый объект с отфильтрованными находками: summary модели сохраняем как есть. */
    static ObjectNode rebuild(JsonNode verdict, ArrayNode kept) {
        ObjectNode result = verdict.deepCopy();
        result.set("findings", kept);
        return result;
    }
}
