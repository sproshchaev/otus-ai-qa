package ru.otus.aiqa.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Правила, которые не нуждаются в модели: их результат воспроизводим и бесплатен.
 * Всё, что ловится регулярным выражением, ловим здесь — модели остаётся то,
 * ради чего её и зовут: смысл теста, риски сопровождения и полнота проверок.
 *
 * Порядок на занятии такой же: сначала правила, потом модель. Обратный порядок
 * означает платить за вызов там, где хватило бы grep.
 */
final class SmellDetector {

    /** Одна находка правил: где, что и насколько это важно. */
    record Smell(String rule, int line, String severity, String message) {
    }

    private static final Pattern SLEEP = Pattern.compile("\\bThread\\.sleep\\s*\\(");
    private static final Pattern ORDER = Pattern.compile("@Order\\s*\\(|MethodOrderer");
    private static final Pattern HARDCODED_URL = Pattern.compile("\"https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?");
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final Pattern WEAK_NAME = Pattern.compile("\\bvoid\\s+test\\w{0,3}\\s*\\(");
    private static final Pattern ASSERT_CALL = Pattern.compile(
            "\\b(assertTrue|assertFalse|assertEquals|assertNotNull|assertNull)\\s*\\(");
    private static final Pattern STATIC_MUTABLE = Pattern.compile(
            "(?m)^\\s*(?:private|protected|public)?\\s*static\\s+(?!final\\b)[A-Za-z_$][\\w.<>\\[\\]$]*\\s+\\w+\\s*[=;]");

    private SmellDetector() {
    }

    static List<Smell> scan(String source) {
        List<Smell> found = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int number = i + 1;

            if (SLEEP.matcher(line).find()) {
                found.add(new Smell("sleep", number, "major",
                        "Thread.sleep фиксирует ожидание временем, а не условием: на медленном раннере тест падает, "
                                + "на быстром тратит секунды впустую"));
            }
            if (ORDER.matcher(line).find()) {
                found.add(new Smell("order-dependence", number, "major",
                        "Порядок выполнения задан явно: тесты нельзя запустить по одному и нельзя распараллелить"));
            }
            if (HARDCODED_URL.matcher(line).find()) {
                found.add(new Smell("hardcoded-url", number, "minor",
                        "Адрес стенда зашит в код: тот же тест не переносится на другое окружение без правки"));
            }
            if (EMPTY_CATCH.matcher(line).find()) {
                found.add(new Smell("swallowed-exception", number, "blocker",
                        "Пустой catch гасит падение: тест зелёный независимо от того, что произошло"));
            }
            if (WEAK_NAME.matcher(line).find()) {
                found.add(new Smell("meaningless-name", number, "minor",
                        "Имя теста не называет проверяемое поведение: по отчёту о падении непонятно, что сломалось"));
            }
            if (hasBareAssert(line)) {
                found.add(new Smell("assert-without-message", number, "major",
                        "Ассерт без сообщения: в отчёте останется «expected true», без указания, "
                                + "какое требование нарушено"));
            }
        }

        found.addAll(scanEmptyCatchBlocks(lines));
        found.addAll(scanStaticState(source));
        found.addAll(scanCopyPaste(lines));
        found.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return found;
    }

    /** Ассерт с минимальным числом аргументов — сообщения нет. Аргументы считаем по балансу скобок, а не по запятым. */
    private static boolean hasBareAssert(String line) {
        Matcher m = ASSERT_CALL.matcher(line);
        while (m.find()) {
            String name = m.group(1);
            int expected = name.equals("assertEquals") ? 2 : 1;
            if (countArguments(line, m.end()) == expected) {
                return true;
            }
        }
        return false;
    }

    /** Аргументы верхнего уровня между скобками; вложенные вызовы и содержимое строк не считаем. */
    private static int countArguments(String line, int afterOpenParen) {
        int depth = 1;
        int args = 1;
        boolean inString = false;
        boolean escaped = false;
        boolean any = false;
        for (int i = afterOpenParen; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                any = true;
                continue;
            }
            if (inString) {
                continue;
            }
            if (!Character.isWhitespace(c)) {
                any = true;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return any ? args : 0;
                }
            } else if (c == ',' && depth == 1) {
                args++;
            }
        }
        return -1;   // вызов не закрылся в этой строке — не наше дело, отдадим модели
    }

    /** Пустой catch, разнесённый на несколько строк, регуляркой по одной строке не ловится. */
    private static List<Smell> scanEmptyCatchBlocks(String[] lines) {
        List<Smell> found = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            int catchAt = lines[i].indexOf("catch");
            if (catchAt < 0) {
                continue;
            }
            // «} catch (Exception e) {» начинается с закрывающей скобки от try —
            // смотреть надо только на хвост строки после самого catch,
            // иначе многострочный пустой catch не находится (поймано тестом findsSwallowedException)
            String tail = lines[i].substring(catchAt);
            int braceAt = tail.indexOf('{');
            if (braceAt < 0 || tail.indexOf('}', braceAt) >= 0) {
                continue;   // блок закрылся в этой же строке — это случай регулярного выражения EMPTY_CATCH
            }
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                String next = lines[j].trim();
                if (next.isEmpty()) {
                    continue;
                }
                if (next.equals("}")) {
                    found.add(new Smell("swallowed-exception", i + 1, "blocker",
                            "Пустой catch гасит падение: тест зелёный независимо от того, что произошло"));
                }
                break;
            }
        }
        return found;
    }

    /** Изменяемое статическое поле — обычная причина того, что тесты работают только вместе и только в этом порядке. */
    private static List<Smell> scanStaticState(String source) {
        List<Smell> found = new ArrayList<>();
        Matcher m = STATIC_MUTABLE.matcher(source);
        while (m.find()) {
            found.add(new Smell("shared-mutable-state", lineOf(source, m.start()), "major",
                    "Изменяемое статическое поле связывает тесты между собой: "
                            + "результат зависит от того, что отработало раньше"));
        }
        return found;
    }

    /** Три и больше одинаковых блоков по четыре строки — копипаста, которую придётся править во всех местах сразу. */
    private static List<Smell> scanCopyPaste(String[] lines) {
        List<Smell> found = new ArrayList<>();
        Map<String, List<Integer>> windows = new HashMap<>();
        int size = 4;
        for (int i = 0; i + size <= lines.length; i++) {
            StringBuilder key = new StringBuilder();
            int meaningful = 0;
            for (int j = i; j < i + size; j++) {
                // экранированную кавычку внутри литерала прячем до разбора: иначе
                // .body("{\"name\":\"Ivan\"}") и .body("{\"name\":\"Anna\"}") дают разные ключи
                // и копипаста тел запросов не находится
                String normalized = lines[j]
                        .replace("\\\"", "")
                        .replaceAll("\"[^\"]*\"", "\"S\"")
                        .replaceAll("\\s+", "");
                if (!normalized.isEmpty() && !normalized.equals("}") && !normalized.equals("{")) {
                    meaningful++;
                }
                key.append(normalized).append('|');
            }
            if (meaningful >= 3) {
                windows.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(i + 1);
            }
        }
        // сдвинутые на строку окна описывают одну и ту же копипасту — в отчёт идёт одна запись
        List<List<Integer>> repeats = new ArrayList<>(windows.values().stream()
                .filter(positions -> positions.size() >= 3)
                .sorted(Comparator.comparingInt(positions -> positions.get(0)))
                .toList());
        int reportedUpTo = Integer.MIN_VALUE;
        for (List<Integer> positions : repeats) {
            if (positions.get(0) <= reportedUpTo) {
                continue;
            }
            reportedUpTo = positions.get(0) + size - 1;
            found.add(new Smell("copy-paste", positions.get(0), "minor",
                    "Один и тот же блок повторён " + positions.size() + " раза (строки " + positions
                            + "): правка контракта потребует одинаковых изменений в каждом месте"));
        }
        return found;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
