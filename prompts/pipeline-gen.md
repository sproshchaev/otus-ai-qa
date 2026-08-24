<!-- версия промпта: 1 (23.08.2026) -->
Сгенерируй workflow GitHub Actions для многомодульного Maven-проекта.

Вход:
- Java 21, Maven, модули: user-service (Spring Boot), api-tests (JUnit 5 + RestAssured), triage
- тесты ходят в поднятый сервис по HTTP на localhost:8080
- отчёт Surefire нужен как артефакт сборки
- после тестов запускается advisory-шаг, который не должен ронять сборку

Требования к результату:
- только YAML, без пояснений вокруг
- запуск на pull_request и workflow_dispatch
- явный блок permissions с минимальными правами
- кэш зависимостей Maven
- таймаут на job

Помни: сгенерированный workflow — черновик. Его проверяют actionlint и ревью человеком.
