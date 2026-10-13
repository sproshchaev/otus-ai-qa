# Прогон evals: golden dataset

Снято 13.10.2026 на `llama3.1:8b` (Ollama 0.32.15, Apple M2 Pro), temperature 0, seed 42.
Команда — в README, раздел «Занятие 29». Это снимок прогона, а не живой результат:
модель может ответить иначе, см. «Неожиданности» в README.

#### Промпт 3.1 с защитой выхода — golden dataset

Модель `llama3.1:8b` · промпт `prompts/triage.md`, версия 3.1 (13.10.2026) · защита выхода: включена

Набор `datasets/triage-golden.jsonl`: записей **13**, совпало **11** — **85 %**, порог 80 %. Время прогона 50.1 с.

**Итог: регрессии нет.**

| id | ожидали | получили | итог | с |
|---|---|---|---|---|
| G01 | `bug` | `bug` | ✅ совпало | 5.0 |
| G02 | `flaky` | `flaky` | ✅ совпало | 3.6 |
| G03 | `env` | `env` | ✅ совпало | 3.9 |
| G04 | `bug` | `bug` | ✅ совпало | 3.9 |
| G05 | `bug` | `bug` | ✅ совпало | 3.8 |
| G06 | `bug` | `bug` | ✅ совпало | 3.7 |
| G07 | `bug` | `bug` | ✅ совпало | 3.9 |
| G08 | `env` | `env` | ✅ совпало | 3.6 |
| G09 | `flaky` | `отказ` | ❌ провал | 3.7 |
| G10 | `bug` | `bug` | ✅ совпало | 3.9 |
| G11 | `env` | `env` | ✅ совпало | 3.8 |
| G12 | `env` | `bug` | ❌ провал | 3.8 |
| G13 | `bug` | `bug` | ✅ совпало | 3.5 |

<details><summary>Ответы модели по каждой записи</summary>

**G01** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов выявил одну упавшую тестовую ситуацию.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserPaginationTest#pagesCoverAllUsers"],
      "hypothesis": "В методе pagesCoverAllUsers UserPaginationTest ожидается, что постраничный обход вернет ровно total элементов, но получено другое значение.",
      "confidence": 1.0
    }
  ]
}
```

**G02** — ожидали `flaky`, получили `flaky`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "flaky",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#activatesUser"],
      "hypothesis": "Повторный прогон может пройти успешно.",
      "confidence": 0.5
    }
  ]
}
```

**G03** — ожидали `env`, получили `env`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "env",
      "tests": ["ru.otus.aiqa.apitests.NotifierIntegrationTest#sendsWelcomeEmail"],
      "hypothesis": "Соединение с сервисом на порту 9099 не установлено.",
      "confidence": 1.0
    }
  ]
}
```

**G04** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#returnsNotFoundForUnknownUser"],
      "hypothesis": "Ожидаемый статус 404, но получен 500",
      "confidence": 1.0
    }
  ]
}
```

**G05** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#deletesUser"],
      "hypothesis": "Ожидается статус 404, но получен 204 при удалении пользователя.",
      "confidence": 1.0
    }
  ]
}
```

**G06** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#readsUser"],
      "hypothesis": "Тест readsUser не может найти пользователя с именем Анна.",
      "confidence": 1.0
    }
  ]
}
```

**G07** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserValidationTest#checksNameLength"],
      "hypothesis": "Ожидаемое значение HTTP-статуса не совпадает с фактическим.",
      "confidence": 1.0
    }
  ]
}
```

**G08** — ожидали `env`, получили `env`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "env",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#updatesUser"],
      "hypothesis": "Сервис user-service.local недоступен.",
      "confidence": 1.0
    }
  ]
}
```

**G09** — ожидали `flaky`, получили `отказ`, провал

Причина: ответ отклонён защитой: упавший тест ru.otus.aiqa.apitests.UserCrudTest#updatesUser помечен как «slow»

**G10** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserValidationTest#rejectsMalformedEmail"],
      "hypothesis": "Ожидаемый статус кода 400, но получен 201.",
      "confidence": 1.0
    }
  ]
}
```

**G11** — ожидали `env`, получили `env`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "env",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#createsUser"],
      "hypothesis": "Сервис не доступен на порту 8080.",
      "confidence": 1.0
    }
  ]
}
```

**G12** — ожидали `env`, получили `bug`, провал

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#deletesUser"],
      "hypothesis": "В методе deletesUser класса UserCrudTest произошла ошибка при вызове метода statusCode.",
      "confidence": 1.0
    }
  ]
}
```

**G13** — ожидали `bug`, получили `bug`, совпало

```json
{
  "summary": "Прогон API-тестов завершился с 1 упавшим тестом.",
  "findings": [
    {
      "reason": "bug",
      "tests": ["ru.otus.aiqa.apitests.UserCrudTest#activatesUser"],
      "hypothesis": "Тест активации пользователя не работает корректно.",
      "confidence": 1.0
    }
  ]
}
```

</details>
