package bot.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

public class DbInitializer {
    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    public static void initDatabase(Connection connection) {
        try {
            InputStream is = DbInitializer.class.getResourceAsStream("/init-db.sql");
            if (is == null) {
                log.warn("Файл init-db.sql не найден в classpath! Пропуск инициализации.");
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("--") || line.isEmpty()) continue;
                    sb.append(line).append(" ");
                    if (line.endsWith(";")) {
                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute(sb.toString());
                        }
                        sb.setLength(0);
                    }
                }
            }

            // Очистка возможных исторических дубликатов по chatId (оставляем только первую запись)
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DELETE FROM users WHERE rowid NOT IN (SELECT MIN(rowid) FROM users GROUP BY chatId)");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_chatId ON users (chatId)");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_tableName ON projects (tableName)");
            } catch (Exception e) {
                log.debug("Индексы/дедупликация: {}", e.getMessage());
            }

            log.info("✓ Схема базы данных проверена и обновлена из init-db.sql");
        } catch (Exception e) {
            log.error("✗ Ошибка при инициализации базы данных", e);
            throw new RuntimeException("Не удалось инициализировать базу данных", e);
        }
    }
}
