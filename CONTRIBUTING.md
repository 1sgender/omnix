# Contributing to OMNIX

Спасибо за интерес к проекту. Проект проприетарный (см. [LICENSE](LICENSE)) —
вклад принимается через pull request'ы в этот репозиторий, что означает согласие
с Contributor Terms из LICENSE (пункт 5): владелец получает бессрочное право
использовать ваш вклад в проекте на любом лицензировании.

## Как работать

1. Ветка от `main`: `feat/<topic>`, `fix/<topic>`, `docs/<topic>`.
2. Один PR — одна логическая задача. Без массовых «заодно»-правок.
3. Перед изменением крупной подсистемы сначала опишите в PR-описании:
   текущее поведение → реальная проблема → минимальное корректное улучшение.
4. Существующий работающий код не переписывается без измеримой выгоды
   (architectural / reliability / security / performance / product).

## Требования к коду

- Kotlin, стиль существующего кода: подробные KDoc-комментарии на русском
  объясняют «почему», не «что».
- Никаких захардкоженных секретов, токенов, ключей, реальных номеров телефонов.
- Логи не содержат: prompt'ов, распознанной речи, токенов, содержимого экрана
  (только длины/хеши/имена компонентов).
- Честные результаты инструментов: никогда не возвращать `SUCCESS`, если
  действие не выполнено (см. `docs/ANDROID_CAPABILITIES.md`).

## Обязательные проверки перед PR

```bash
bash ./gradlew :server:compileKotlin :app:compileDevDebugKotlin   # компиляция
bash ./gradlew :app:testDevDebugUnitTest                          # JVM-тесты app
bash ./gradlew phase3StaticAnalysis                               # detekt
bash ./gradlew :app:lintDevDebug                                  # Android lint
bash ./gradlew :server:test                                       # требует PostgreSQL (см. README)
bash scripts/verify-architectural-invariants.sh                   # инварианты архитектуры
```

CI прогоняет то же самое + Trivy/Gitleaks/SBOM; PR без зелёного CI не мержится.
Каждый исправленный P0/P1-баг получает regression-тест.

## Branch protection (политика слияния в main)

Main защищён (включая владельца — enforce_admins):

- изменения **только через pull request** — прямые пуши в main отклоняются;
- обязательны **все 5 статус-чеков**: `compile-gate (server + app debug)`,
  `build`, `Android API 34 instrumentation and coverage`,
  `source-security`, `production-image-security`;
- force-push и удаление ветки main запрещены;
- merge-коммиты разрешены (linear history не требуется).

Если добавляете/переименовываете job в CI — обновите список required
status checks в настройках ветки main, иначе PR-ы «зависнут» на
ожидании чека, который больше не приходит.

## Коммиты

Повелительное наклонение, первая строка ≤ 72 символов, тело объясняет «почему»:
`Fix confirmation-token replay across restarts (CR-04)`.

## Отчёты об уязвимостях

Только приватно — см. [SECURITY.md](SECURITY.md). Никаких деталей эксплойтов
в публичных issue.
