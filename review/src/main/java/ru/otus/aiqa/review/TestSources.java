package ru.otus.aiqa.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Что именно уходит на ревью. Разбор всего репозитория на каждом коммите — это
 * оплаченный впустую вызов: в PR меняются два файла, а не двести. Поэтому есть
 * два входа — каталог (для разового прогона) и список изменённых файлов (для конвейера).
 */
final class TestSources {

    /** Файл теста: путь для отчёта и его содержимое. */
    record TestFile(Path path, String source) {

        int lines() {
            return source.split("\n", -1).length;
        }
    }

    private TestSources() {
    }

    /** Все java-файлы тестов внутри каталога, по возрастанию пути — чтобы прогон повторялся. */
    static List<TestFile> fromDirectory(Path root, int limit) throws IOException {
        if (!Files.exists(root)) {
            throw new IllegalStateException("не найден путь " + root.toAbsolutePath());
        }
        if (Files.isRegularFile(root)) {
            return List.of(read(root));
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(limit)
                    .toList();
            return readAll(files);
        }
    }

    /**
     * Список путей из файла — то, что кладёт в конвейер `git diff --name-only`.
     * Несуществующие и не-java строки пропускаем молча: в diff попадают и удалённые файлы,
     * и pom.xml, и картинки, а падать на этом шаг не имеет права.
     */
    static List<TestFile> fromChangedList(Path listFile, int limit) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String line : Files.readAllLines(listFile)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.endsWith(".java")) {
                continue;
            }
            Path candidate = Path.of(trimmed);
            if (Files.isRegularFile(candidate) && paths.size() < limit) {
                paths.add(candidate);
            }
        }
        return readAll(paths);
    }

    private static List<TestFile> readAll(List<Path> paths) throws IOException {
        List<TestFile> files = new ArrayList<>();
        for (Path path : paths) {
            files.add(read(path));
        }
        return files;
    }

    private static TestFile read(Path path) throws IOException {
        return new TestFile(path, Files.readString(path));
    }
}
