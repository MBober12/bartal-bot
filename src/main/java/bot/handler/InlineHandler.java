package bot.handler;

import bot.model.*;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class InlineHandler {
    public InlineHandler(ProjectRepository projectRepo, UserRepository userRepo) {
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
    }
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;


    public DocResult handleInlineQuery(long chatId, String inlineQueryId, String queryData) {
        DocResult result = new DocResult();
        TilesList tiles = new TilesList();

        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            // Если пользователя нет в базе — отдаем пустой ответ
            result.addTask(new BotTask(BotTask.Type.ANSWER_INLINE_QUERY, inlineQueryId, tiles));
            return result;
        }

        User user = userOpt.get();
        List<Project> userProjects;

        UserState currentState = UserState.fromText(user.getState());
        if (currentState == UserState.ENTERING_PROJECT_NAME) {
            // При просмотре информации о проекте отдаем все проекты (включая архивные)
            userProjects = projectRepo.findAll();
        } else {
            // При отправке документов отдаем только активные проекты пользователя
            userProjects = projectRepo.getProjectsForUserAndRole(user, Role.USER);
        }

        if (userProjects == null || userProjects.isEmpty()) {
            result.addTask(new BotTask(BotTask.Type.ANSWER_INLINE_QUERY, inlineQueryId, tiles));
            return result;
        }

        // Ранжируем и фильтруем проекты напрямую
        List<Project> sortedProjects = rankProjects(userProjects, queryData);

        // Лимит Telegram API на одну выдачу — 50 результатов
        int limit = Math.min(sortedProjects.size(), 50);
        for (int i = 0; i < limit; i++) {
            Project project = sortedProjects.get(i);

            String desc = project.getDescription();
            if (project.isArchived()) {
                desc = (desc == null || desc.isBlank()) ? "[В АРХИВЕ]" : "[В АРХИВЕ] " + desc;
            }

            // Порядок полей: type ("article"), id (уникальный!), title, description/messageText
            tiles.addTile(new InlineQueryTile(
                    "article",
                    project.getProjectName(),
                    desc,
                    project.getProjectName()
            ));
        }

        result.addTask(new BotTask(BotTask.Type.ANSWER_INLINE_QUERY, inlineQueryId, tiles));
        return result;
    }


    private int getLevenshteinDistance(String s1, String s2) {
        int[] dp = new int[s2.length() + 1];
        for (int i = 0; i <= s2.length(); i++) dp[i] = i;

        for (int i = 1; i <= s1.length(); i++) {
            int prev = i;
            for (int j = 1; j <= s2.length(); j++) {
                int val = Math.min(
                        Math.min(dp[j] + 1, prev + 1),
                        dp[j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                );
                dp[j - 1] = prev;
                prev = val;
            }
            dp[s2.length()] = prev;
        }
        return dp[s2.length()];
    }

    /**
     * Фильтрует и ранжирует проекты по релевантности запросу.
     */
    private List<Project> rankProjects(List<Project> projects, String rawQuery) { if (projects == null || projects.isEmpty()) { return Collections.emptyList(); }
// Если в строке ничего не ввели (или только пробелы) — возвращаем ВСЕ 13 проектов!
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new ArrayList<>(projects);
        }

        String query = rawQuery.trim().toLowerCase();
        List<Project> matched = new ArrayList<>();

        for (Project p : projects) {
            if (p == null || p.getProjectName() == null) continue;

            String name = p.getProjectName().trim().toLowerCase();

            // Простое и надежное вхождение подстроки
            if (name.contains(query)) {
                matched.add(p);
            }
        }

        return matched;
    }

    private int calculateScore(String name, String fullQuery, String[] queryWords) {
        // Проверяем, чтобы КАЖДОЕ слово из запроса встречалось в названии
        for (String word : queryWords) {
            if (!name.contains(word)) {
                return 0; // Если хотя бы одного слова нет — проект не подходит
            }
        }

        int score = 100; // Базовый балл за то, что все слова найдены

        // Бонус: полное точное совпадение
        if (name.equals(fullQuery)) {
            score += 500;
        }
        // Бонус: название начинается с запроса
        else if (name.startsWith(fullQuery)) {
            score += 300;
        }
        // Бонус: содержит целую фразу подряд
        else if (name.contains(fullQuery)) {
            score += 150;
        }

        // Чем короче название проекта при прочих равных, тем оно выше в списке
        score += Math.max(0, 50 - name.length());

        return score;
    }

    /**
     * Оценка совпадения: чем выше число, тем выше результат в списке.
     */
    private int calculateScore(String name, String query) {
        // 1. Полное точное совпадение
        if (name.equals(query)) {
            return 1000;
        }

        // 2. Начинается с искомого текста (например, запрос "авто", проект "Автопарк")
        if (name.startsWith(query)) {
            return 500 + (100 - Math.min(name.length(), 100)); // Короткие названия при прочих равных выше
        }

        // 3. Одно из слов внутри названия начинается с запроса (например, "Новый Автопарк")
        if (name.contains(" " + query)) {
            return 300;
        }

        // 4. Подстрока встречается в любой части слова
        if (name.contains(query)) {
            return 200;
        }

        // 5. Нечеткий поиск (Левенштейн) для компенсации опечаток
        if (query.length() >= 3) {
            int distance = getLevenshteinDistance(name, query);
            int maxAllowedErrors = query.length() > 5 ? 2 : 1;

            if (distance <= maxAllowedErrors) {
                return 100 - distance * 20;
            }
        }

        return 0; // Не подошло
    }
}
