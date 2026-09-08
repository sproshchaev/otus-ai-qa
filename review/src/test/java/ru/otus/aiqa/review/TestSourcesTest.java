package ru.otus.aiqa.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * В diff попадают не только тесты: удалённые файлы, pom.xml, картинки.
 * Шаг обязан это пережить, а не упасть на первой же строке списка.
 */
class TestSourcesTest {

    @Test
    @DisplayName("Из списка изменённых берутся только существующие java-файлы")
    void filtersChangedList(@TempDir Path dir) throws IOException {
        Path kept = Files.writeString(dir.resolve("KeptTest.java"), "class KeptTest {}");
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path list = Files.writeString(dir.resolve("changed.txt"), String.join("\n",
                kept.toString(),
                dir.resolve("pom.xml").toString(),
                dir.resolve("DeletedTest.java").toString(),
                ""));

        List<TestSources.TestFile> files = TestSources.fromChangedList(list, 10);

        assertEquals(1, files.size(), "остаться должен только существующий java-файл");
        assertEquals(kept, files.get(0).path(), "путь должен совпасть с исходным");
    }

    @Test
    @DisplayName("Предел на число файлов соблюдается: конвейер не платит за весь репозиторий")
    void respectsLimit(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("T" + i + "Test.java"), "class T" + i + "Test {}");
        }

        assertEquals(2, TestSources.fromDirectory(dir, 2).size(), "должно вернуться не больше предела");
    }

    @Test
    @DisplayName("Каталог обходится в устойчивом порядке")
    void sortsDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("BTest.java"), "class BTest {}");
        Files.writeString(dir.resolve("ATest.java"), "class ATest {}");

        List<TestSources.TestFile> files = TestSources.fromDirectory(dir, 10);

        assertTrue(files.get(0).path().getFileName().toString().startsWith("A"),
                "первым должен идти файл, меньший по пути");
    }

    @Test
    @DisplayName("Число строк считается по файлу, а не по содержимому в памяти")
    void countsLines(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("CTest.java"), "class CTest {\n}\n");

        assertEquals(3, TestSources.fromDirectory(dir, 10).get(0).lines(),
                "две строки и завершающий перевод строки дают три элемента");
    }
}
