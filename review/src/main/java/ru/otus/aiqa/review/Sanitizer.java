package ru.otus.aiqa.review;

import java.util.regex.Pattern;

/**
 * В промпт уходит всё, что попало в логи и отчёты, — значит перед отправкой это надо чистить.
 * Правило простое: маскируем то, чего модель видеть не должна, даже когда модель локальная.
 * Локальность снимает вопрос передачи наружу, но не снимает логирование и артефакты сборки.
 */
final class Sanitizer {

    // значение маскируем до конца строки: у «Authorization: Bearer <токен>» секрет во втором слове,
    // и обрезка по первому \S оставила бы его в промпте — поймано тестом masksBearerToken
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?im)\\b(token|secret|password|passwd|api[-_]?key|access[-_]?key|authorization)\\b\\s*[:=]\\s*\\S.*$");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}");
    private static final Pattern LONG_OPAQUE = Pattern.compile("\\b[A-Za-z0-9+/_-]{32,}={0,2}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");

    private Sanitizer() {
    }

    static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = KEY_VALUE.matcher(text).replaceAll("$1=***");
        masked = BEARER.matcher(masked).replaceAll("Bearer ***");
        masked = LONG_OPAQUE.matcher(masked).replaceAll("***");
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        return masked;
    }
}
