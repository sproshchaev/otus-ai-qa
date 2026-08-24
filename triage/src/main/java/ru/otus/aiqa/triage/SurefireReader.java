package ru.otus.aiqa.triage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Вход LLM-шага — обычный отчёт Surefire. Ничего не конвертируем: читаем то,
 * что сборка и так кладёт в target/surefire-reports.
 */
final class SurefireReader {

    record TestCase(String className, String name, double timeSeconds, String failureType, String failureMessage) {
        boolean failed() {
            return failureType != null;
        }
    }

    record RunReport(List<TestCase> cases, int total, int failed, double totalSeconds) {
    }

    private SurefireReader() {
    }

    static RunReport read(Path path) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).forEach(files::add);
            }
        } else {
            files.add(path);
        }
        if (files.isEmpty()) {
            throw new IOException("не найдено ни одного отчёта Surefire в " + path);
        }

        List<TestCase> cases = new ArrayList<>();
        double seconds = 0;
        for (Path file : files) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder().parse(file.toFile());
            NodeList nodes = doc.getElementsByTagName("testcase");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element testcase = (Element) nodes.item(i);
                double time = parseDouble(testcase.getAttribute("time"));
                seconds += time;
                Element failure = firstChild(testcase, "failure");
                if (failure == null) {
                    failure = firstChild(testcase, "error");
                }
                cases.add(new TestCase(
                        testcase.getAttribute("classname"),
                        testcase.getAttribute("name"),
                        time,
                        failure == null ? null : failure.getAttribute("type"),
                        failure == null ? null : shorten(failure.getTextContent())));
            }
        }
        int failed = (int) cases.stream().filter(TestCase::failed).count();
        return new RunReport(cases, cases.size(), failed, seconds);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** В промпт уходит хвост стектрейса, а не весь: длинный вход дороже и хуже разбирается. */
    private static String shorten(String text) {
        String[] lines = text.strip().split("\n");
        int limit = Math.min(lines.length, 12);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, limit));
    }
}
