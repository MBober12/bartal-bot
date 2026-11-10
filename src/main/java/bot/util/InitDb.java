package bot.util;

import bot.repository.DbInitializer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

public class InitDb {
    public static void main(String[] args) {
        Properties config = new Properties();
        try (InputStream is = new FileInputStream("config.properties")) {
            config.load(is);
        } catch (Exception ignored) {}

        String dbPath = config.getProperty("db.path", "bartal-bot.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            DbInitializer.initDatabase(conn);
            System.out.println("Database initialized successfully at: " + dbPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
