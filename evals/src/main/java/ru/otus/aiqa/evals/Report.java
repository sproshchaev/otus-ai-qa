package ru.otus.aiqa.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** Отчёт прогона в двух видах: markdown — человеку и в сводку CI, JSON — для сравнения прогонов. */
final class Report {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int QUOTE_LIMIT = 400;

    private Report() {
    }

    static String markdown(EvalRunner.Summary s) {
        EvalRunner.Settings cfg = s.settings();
        boolean redteam = cfg.mode() == EvalRunner.Mode.REDTEAM;
        StringBuilder md = new StringBuilder();
        md.append("### Evals: ").append(redteam ? "набор атак (red teaming)" : "golden dataset").append("\n\n");
        md.append("Модель `").append(cfg.model()).append("` · промпт `").append(cfg.prompt())
                .append("`, версия ").append(cfg.promptVersion())
                .append(" · защита выхода: ").append(cfg.guard() ? "включена" : "выключена").append("\n\n");
        md.append("Набор `").append(cfg.dataset()).append("`: записей **").append(s.total()).append("**, ");
        if (redteam) {
            md.append("пробило **").append(s.breaches()).append("**, порог — не больше ")
                    .append(cfg.maxBreaches()).append(". ");
            md.append("Категория удержана в ").append(s.passed()).append(" из ").append(s.total()).append(". ");
        } else {
            md.append("совпало **").append(s.passed()).append("** — **")
                    .append(percent(s.passRate())).append("**, порог ").append(percent(cfg.minPass())).append(". ");
        }
        md.append("Время прогона ").append(String.format(Locale.ROOT, "%.1f", s.seconds())).append(" с.\n\n");
        md.append(s.ok() ? "**Итог: регрессии нет.**\n\n" : "**Итог: РЕГРЕССИЯ — порог не выдержан.**\n\n");

        if (redteam) {
            md.append("| id | атака | цель атаки | ожидали | получили | итог | с |\n|---|---|---|---|---|---|---|\n");
        } else {
            md.append("| id | ожидали | получили | итог | с |\n|---|---|---|---|---|\n");
        }
        for (Scoring.Outcome o : s.outcomes()) {
            EvalCase c = o.evalCase();
            md.append("| ").append(c.id()).append(" | ");
            if (redteam) {
                md.append(c.attack()).append(" | `").append(c.target()).append("` | ");
            }
            md.append("`").append(c.expected()).append("` | `").append(o.got()).append("` | ")
                    .append(o.pass() ? "✅ " : "❌ ").append(o.status()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f", o.seconds())).append(" |\n");
        }

        boolean anyFailed = s.outcomes().stream().anyMatch(o -> !o.pass() || o.leakAttempt());
        if (anyFailed) {
            md.append("\n#### Что ответила модель там, где ожидание не выполнено\n");
            for (Scoring.Outcome o : s.outcomes()) {
                if (o.pass() && !o.leakAttempt()) {
                    continue;
                }
                md.append("\n**").append(o.evalCase().id()).append("** — ").append(o.status())
                        .append(": ").append(o.detail() == null ? "" : o.detail()).append("\n");
                if (o.raw() != null) {
                    md.append("\n```json\n").append(shorten(o.raw())).append("\n```\n");
                }
            }
        }
        return md.toString();
    }

    static String json(EvalRunner.Summary s) throws Exception {
        EvalRunner.Settings cfg = s.settings();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("mode", cfg.mode().label());
        root.put("model", cfg.model());
        root.put("prompt", cfg.prompt());
        root.put("promptVersion", cfg.promptVersion());
        root.put("dataset", cfg.dataset());
        root.put("guard", cfg.guard());
        root.put("total", s.total());
        root.put("passed", s.passed());
        root.put("passRate", Math.round(s.passRate() * 1000) / 1000.0);
        root.put("breaches", s.breaches());
        root.put("minPass", cfg.minPass());
        root.put("maxBreaches", cfg.maxBreaches());
        root.put("seconds", Math.round(s.seconds() * 10) / 10.0);
        root.put("ok", s.ok());
        ArrayNode cases = root.putArray("cases");
        for (Scoring.Outcome o : s.outcomes()) {
            ObjectNode node = cases.addObject();
            node.put("id", o.evalCase().id());
            if (o.evalCase().isAttack()) {
                node.put("attack", o.evalCase().attack());
                node.put("target", o.evalCase().target());
            }
            node.put("expected", o.evalCase().expected());
            node.put("got", o.got());
            node.put("status", o.status());
            node.put("pass", o.pass());
            node.put("breach", o.breach());
            node.put("seconds", Math.round(o.seconds() * 10) / 10.0);
            node.put("detail", o.detail());
            node.put("raw", o.raw());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String percent(double value) {
        return Math.round(value * 100) + " %";
    }

    private static String shorten(String raw) {
        String text = raw.strip();
        return text.length() <= QUOTE_LIMIT ? text : text.substring(0, QUOTE_LIMIT) + " …";
    }
}
