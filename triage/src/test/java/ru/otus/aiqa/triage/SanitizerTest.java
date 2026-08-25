package ru.otus.aiqa.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Санитизация входа перед отправкой в модель")
class SanitizerTest {

    @Test
    @DisplayName("Пары «ключ: значение» с секретами маскируются")
    void masksSecretKeyValues() {
        String masked = Sanitizer.mask("api_key: 1234abcd, password=qwerty123");

        assertFalse(masked.contains("1234abcd"), "ключ не должен уйти в промпт");
        assertFalse(masked.contains("qwerty123"), "пароль не должен уйти в промпт");
    }

    @Test
    @DisplayName("Заголовок Authorization: Bearer маскируется")
    void masksBearerToken() {
        assertEquals("Authorization=***", Sanitizer.mask("Authorization: Bearer abcdefghijklmnop"));
    }

    @Test
    @DisplayName("Длинные непрозрачные строки маскируются как возможные секреты")
    void masksLongOpaqueStrings() {
        String masked = Sanitizer.mask("payload eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdef done");

        assertTrue(masked.contains("***"), "длинная строка должна быть замаскирована");
        assertTrue(masked.startsWith("payload "), "остальной текст должен сохраниться");
    }

    @Test
    @DisplayName("Адреса электронной почты маскируются как персональные данные")
    void masksEmails() {
        assertEquals("создан ***@*** ok", Sanitizer.mask("создан ivan.petrov@example.com ok"));
    }

    @Test
    @DisplayName("Обычный текст падения не портится")
    void keepsOrdinaryText() {
        String text = "expected: <7> but was: <6> at UserPaginationTest.java:41";

        assertEquals(text, Sanitizer.mask(text));
    }
}
