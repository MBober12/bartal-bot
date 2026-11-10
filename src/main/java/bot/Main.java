package bot;

import bot.apiClient.TelegramApiClient;
import bot.handler.CallbackHandler;
import bot.handler.FileHandler;
import bot.handler.InlineHandler;
import bot.handler.TextHandler;
import bot.model.Role;
import bot.model.User;
import bot.repository.PaperRepository;
import bot.repository.ProjectRepository;
import bot.repository.ReminderRepository;
import bot.repository.UserRepository;
import bot.scheduler.Reminder;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Начинаю запуск Телеграм Бота");
        Properties config = new Properties();
        try (FileInputStream configStream = new FileInputStream("config.properties")) {
            config.load(configStream);
        } catch (IOException e) {
            log.error("✗ Ошибка чтения config.properties", e);
            System.exit(1);
            return;
        }

        try {
            log.info("✓ Конфиг загружен");
            String botToken = config.getProperty("bot.token");
            if (botToken == null || botToken.isBlank()) {
                throw new IllegalArgumentException("В config.properties не задан обязательный параметр bot.token");
            }
            String adminChatIdValue = config.getProperty("admin.chatId");
            if (adminChatIdValue == null || !adminChatIdValue.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("В config.properties не задан корректный параметр admin.chatId");
            }
            long adminChatId;
            try {
                adminChatId = Long.parseLong(adminChatIdValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Параметр admin.chatId выходит за диапазон Telegram ID", e);
            }

            String dbPath = config.getProperty("db.path", "bartal-bot.db");
            String projectsFile = config.getProperty("projects.file", "projects.xlsx");

            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            log.info("✓ База данных подключена: {}", dbPath);

            bot.repository.DbInitializer.initDatabase(connection);

            UserRepository userRepo = new UserRepository(connection);
            if (userRepo.countByRole(Role.ADMIN) == 0) {
                User admin = userRepo.findById(adminChatId).orElseGet(() -> new User(adminChatId, "admin"));
                if (admin.getName() == null || admin.getName().isBlank()) {
                    admin.setName("admin");
                }
                admin.addRole(Role.ADMIN);
                admin.addRole(Role.USER);
                userRepo.save(admin);
                log.info("✓ Первый администратор создан и настроен: chatId={}", adminChatId);
            } else {
                log.info("✓ В базе уже есть администраторы ({} чел.). Автоперезапись пропущена.", userRepo.countByRole(Role.ADMIN));
            }

            ReminderRepository reminderRepo = new ReminderRepository(connection);
            ProjectRepository projectRepo = new ProjectRepository(connection, projectsFile, userRepo);
            PaperRepository paperRepo = new PaperRepository(connection, projectRepo);

            CallbackHandler callbackHandler = new CallbackHandler(paperRepo, userRepo, projectRepo);
            TextHandler textHandler = new TextHandler(paperRepo, userRepo, projectRepo, reminderRepo);
            FileHandler fileHandler = new FileHandler(paperRepo, userRepo, projectRepo);
            InlineHandler inlineHandler = new InlineHandler(projectRepo, userRepo);

            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(75, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();

            TelegramApiClient apiClient = new TelegramApiClient(httpClient, botToken);
            Bot bot = new Bot(botToken, callbackHandler, textHandler, fileHandler, inlineHandler, userRepo, paperRepo, projectRepo, reminderRepo, apiClient);

            TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication(
                    () -> new ObjectMapper(), () -> httpClient);
            log.info("✓ Регистрирую бота...");

            app.registerBot(botToken, bot);

            Reminder reminder = new Reminder(reminderRepo, bot);
            ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
            executor.scheduleAtFixedRate(reminder::run, Reminder.getDelay(), 30, TimeUnit.MINUTES);

            log.info("✓ Телеграм бот успешно запущен!");

            Thread.currentThread().join();

        } catch (TelegramApiException e) {
            log.error("✗ Ошибка Telegram API при запуске", e);
            System.exit(1);
        } catch (SQLException e) {
            log.error("✗ Ошибка базы данных при запуске", e);
            System.exit(1);
        } catch (Exception e) {
            log.error("✗ Неизвестная ошибка при запуске", e);
            System.exit(1);
        }
    }
}
