package bot.service;

import bot.model.Role;
import bot.model.Project;
import bot.model.User;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class ProjectImportService extends Columns {
    private static final Logger log = LoggerFactory.getLogger(ProjectImportService.class);
    private final ProjectRepository projectRepo;
    private final UserRepository userRepo;

    public ProjectImportService(ProjectRepository projectRepo, UserRepository userRepo) {
        this.projectRepo = projectRepo;
        this.userRepo = userRepo;
    }

    public ImportResult readFromExcel(File file) throws Exception {
        ImportResult result = new ImportResult();

        // 1. Загружаем всех пользователей через UserRepository
        List<User> allUsers = userRepo.findAll();
        Map<String, Long> nameToChatId = new HashMap<>();
        for (User u : allUsers) {
            if (u.getName() != null) {
                nameToChatId.put(u.getName().trim(), u.getChatId());
            }
        }

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next(); // Заголовок

            while (rows.hasNext()) {
                Row row = rows.next();
                String rawName = getCellString(row, nameColumn);
                String projectName = rawName.replace("~", "")
                        .replace("\r", "")
                        .replace("\n", " ")
                        .trim();

                if (projectName.isEmpty()) continue;

                // Парсим все 4 колонки
                List<String> userNames = parseNames(getCellString(row, usersColumn), projectName, Role.USER, result);
                List<String> approverNames = parseNames(getCellString(row, approversColumn), projectName, Role.APPROVER, result);
                List<String> registrarNames = parseNames(getCellString(row, registrarsColumn), projectName, Role.REGISTRAR, result);
                List<String> receiverNames = parseNames(getCellString(row, receiversColumn), projectName, Role.RECEIVER, result);

                // Фильтруем: оставляем только тех, кто ЕСТЬ в БД
                List<String> validUsers = filterValidNames(userNames, nameToChatId);
                List<String> validApprovers = filterValidNames(approverNames, nameToChatId);
                List<String> validRegistrars = filterValidNames(registrarNames, nameToChatId);
                List<String> validReceivers = filterValidNames(receiverNames, nameToChatId);

                // Собираем не найденных для отчёта (во всех ролях)
                collectNotFound(userNames, validUsers, projectName, allUsers, result);
                collectNotFound(approverNames, validApprovers, projectName, allUsers, result);
                collectNotFound(registrarNames, validRegistrars, projectName, allUsers, result);
                collectNotFound(receiverNames, validReceivers, projectName, allUsers, result);

                // Проверка на "эффективную пустоту"
                Set<Role> emptyRoles = new LinkedHashSet<>();
                if (validUsers.isEmpty()) emptyRoles.add(Role.USER);
                if (validApprovers.isEmpty())  emptyRoles.add(Role.APPROVER);
                if (validRegistrars.isEmpty())  emptyRoles.add(Role.REGISTRAR);
                if (validReceivers.isEmpty())  emptyRoles.add(Role.RECEIVER);

                if (!emptyRoles.isEmpty()) {
                    StringBuilder roleName = new StringBuilder();
                    if (emptyRoles.contains(Role.USER)) roleName.append("пользователи (колонка B)\n");
                    if (emptyRoles.contains(Role.APPROVER)) roleName.append("утверждающие (колонка C)\n");
                    if (emptyRoles.contains(Role.REGISTRAR)) roleName.append("регистраторы (колонка D)\n");
                    if (emptyRoles.contains(Role.RECEIVER)) roleName.append("получатели (колонка E)\n");
                    if (roleName.isEmpty())
                        throw new IllegalStateException("Unexpected value: " + emptyRoles.toString());
                    result.addError("Проект \"" + projectName + "\" — роль не имеет валидных пользователей в БД: " + roleName);
                    result.addEmptyRoles(emptyRoles);
                    continue; // Проект невалиден, пропускаем
                }

                // Проект валиден — считаем уникальных валидных пользователей
                Set<String> allValidNames = new LinkedHashSet<>();
                allValidNames.addAll(validUsers);
                allValidNames.addAll(validApprovers);
                allValidNames.addAll(validRegistrars);
                allValidNames.addAll(validReceivers);

                result.addProject(projectName, 0, allValidNames.size(), 0, null);
            }
        }

        return result;
    }


    // Вспомогательный метод: оставляет только имена, которые есть в БД
    private List<String> filterValidNames(List<String> names, Map<String, Long> nameToChatId) {
        return names.stream()
                .filter(n -> nameToChatId.containsKey(n.trim()))
                .collect(Collectors.toList());
    }

    // Вспомогательный: собирает не найденных в отчёт с подсказками
    private void collectNotFound(List<String> allNames, List<String> validNames, String projectName, List<User> allUsers, ImportResult result) {
        Set<String> validSet = new HashSet<>(validNames);
        for (String name : allNames) {
            if (!validSet.contains(name)) {
                String closest = findClosestMatch(name, allUsers);
                result.addNotFound(name, projectName, closest);
            }
        }
    }



    /**
     * Импортирует проекты из Excel-файла.
     * Структура: проект | пользователи | утверждающие | регистраторы | получатели
     * Имена через запятую. Сопоставляет по имени из таблицы users.
     *
     * @return список созданных проектов и ошибок
     */
    public int importFromExcel(File file) throws Exception {
        // 1. Валидация (твой readFromExcel)
        ImportResult validation = readFromExcel(file);
        if (validation.hasErrors()) {
            throw new IllegalStateException("Ошибки валидации:\n" + validation.getSummary());
        }

        // 2. Подготовка данных для репозитория
        List<User> allUsers = userRepo.findAll();
        Map<String, Long> nameToChatId = new HashMap<>();
        for (User u : allUsers) {
            if (u.getName() != null) nameToChatId.put(u.getName().trim(), u.getChatId());
        }

        List<PendingProject> projectsToSave = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next();

            while (rows.hasNext()) {
                Row row = rows.next();
                String rawName = getCellString(row, nameColumn);
                String projectName = rawName.replace("~", "")
                        .replace("\r", "")
                        .replace("\n", " ")
                        .trim();
                String projectDescription = getCellString(row, descriptionColumn).trim();
                if (projectName.isEmpty()) continue;

                List<String> userNames = parseNames(getCellString(row, usersColumn), projectName, Role.USER);
                List<String> approverNames = parseNames(getCellString(row, approversColumn), projectName, Role.APPROVER);
                List<String> registrarNames = parseNames(getCellString(row, registrarsColumn), projectName, Role.REGISTRAR);
                List<String> receiverNames = parseNames(getCellString(row, receiversColumn), projectName, Role.RECEIVER);

                List<String> validUsers = filterValidNames(userNames, nameToChatId);
                List<String> validApprovers = filterValidNames(approverNames, nameToChatId);
                List<String> validRegistrars = filterValidNames(registrarNames, nameToChatId);
                List<String> validReceivers = filterValidNames(receiverNames, nameToChatId);

                PendingProject pp = new PendingProject(projectName, projectDescription, nameToChatId);

                pp.setUsers(validUsers, Role.USER);
                pp.setUsers(validApprovers, Role.APPROVER);
                pp.setUsers(validRegistrars, Role.REGISTRAR);
                pp.setUsers(validReceivers, Role.RECEIVER);

                projectsToSave.add(pp);
            }
        }

        // ОДИН ВЫЗОВ РЕПОЗИТОРИЯ — вся магия внутри
        projectRepo.saveProjects(projectsToSave);

        return projectsToSave.size();
    }


    // Хелпер: привязка списка пользователей к проекту с флагами ролей
    private void bindUsers(int projectId, List<String> names, Map<String, Long> nameToChatId,
                           List<User> allUsers, boolean isUser, boolean isApprover, boolean isRegistrar, boolean isReceiver) {
        for (String name : names) {
            Long chatId = nameToChatId.get(name.trim().toLowerCase());
            if (chatId == null) continue;

            String displayName = allUsers.stream()
                    .filter(u -> u.getChatId() == chatId)
                    .findFirst().map(User::getName).orElse(name);

            projectRepo.addUser(projectId, chatId, isUser, isApprover, isRegistrar, isReceiver);
        }
    }

    // Очистка всех проектов через репозиторий
    private void clearAllProjects() throws SQLException {
        List<Project> projects = projectRepo.findAllActive();
        for (Project p : projects) {
            projectRepo.deleteProject(p.getId());
        }
    }


    // Вспомогательный метод для привязки списка пользователей к проекту с конкретной ролью
    private void bindProjectUsers(int projectId, List<String> names, Role role, Map<String, Long> nameToChatId, List<User> allUsers) {
        for (String name : names) {
            Long chatId = nameToChatId.get(name.trim().toLowerCase());
            if (chatId == null) continue; // Не должно случиться после валидации

            // Добавляем пользователя с флагами ролей
            boolean isApprover = role == Role.APPROVER;
            boolean isRegistrar = role == Role.REGISTRAR;
            boolean isReceiver = role == Role.RECEIVER;
            boolean isUser = role == Role.USER;

            projectRepo.addUser(projectId, chatId, isUser, isApprover, isRegistrar, isReceiver);
       }
    }


    /**
     * Экспортирует текущие проекты в Excel-файл.
     * Структура: проект | пользователи | утверждающие | регистраторы | получатели
     * Если проектов нет — возвращает шаблон с примером.
     *
     * @return путь к созданному файлу
     */
    public String exportToExcel() {
        List<Project> projects = projectRepo.findAllActive();

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Проекты");

            // Заголовок
            Row header = sheet.createRow(0);
            header.createCell(nameColumn).setCellValue("проект");
            header.createCell(descriptionColumn).setCellValue("описание");
            header.createCell(usersColumn).setCellValue("пользователи");
            header.createCell(approversColumn).setCellValue("утверждающие");
            header.createCell(registrarsColumn).setCellValue("регистраторы");
            header.createCell(receiversColumn).setCellValue("получатели");

            if (projects.isEmpty()) {
                // Шаблон с примером
                Row example = sheet.createRow(1);
                example.createCell(nameColumn).setCellValue("Пример проекта");
                example.createCell(descriptionColumn).setCellValue("Важнейший проект. Завершить до завтра.");
                example.createCell(usersColumn).setCellValue("Иванов Иван, Петров Петр");
                example.createCell(approversColumn).setCellValue("Иванов Иван");
                example.createCell(registrarsColumn).setCellValue("Петров Петр");
                example.createCell(receiversColumn).setCellValue("Иванов Иван, Петров Петр");
            } else {
                int rowNum = 1;
                for (Project p : projects) {
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(nameColumn).setCellValue(p.getProjectName());

                    row.createCell(descriptionColumn).setCellValue(p.getDescription());

                    List<User> users = projectRepo.getUsersByRoleAndProjectId(p.getId(), Role.USER);
                    row.createCell(usersColumn).setCellValue(joinNames(users));

                    List<User> approvers = projectRepo.getUsersByRoleAndProjectId(p.getId(), Role.APPROVER);

                    row.createCell(approversColumn).setCellValue(joinNames(approvers));

                    List<User> registrars = projectRepo.getUsersByRoleAndProjectId(p.getId(), Role.REGISTRAR);
                    row.createCell(registrarsColumn).setCellValue(joinNames(registrars));

                    List<User> receivers = projectRepo.getUsersByRoleAndProjectId(p.getId(), Role.RECEIVER);
                    row.createCell(receiversColumn).setCellValue(joinNames(receivers));
                }
            }

            // Автоподбор ширины
            for (int i = 0; i < 5; i++) {
                sheet.autoSizeColumn(i);
            }

            String filePath = projectRepo.getProjectsFilePath();
            try (var fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
            return filePath;
        } catch (IOException e) {
            log.error("exportToExcel failed", e);
            return null;
        }
    }

    private String joinNames(List<User> users) {
        return users.stream()
                .map(User::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private List<String> parseNames(String cell, String projectName, Role role, ImportResult result) {
        List<String> names = new ArrayList<>();
        if (cell == null || cell.isBlank()) return names;
        for (String part : cell.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (!names.contains(trimmed))
                    names.add(trimmed);
                else {
                    String column = switch (role) {
                        case USER -> "\"пользователи\" (колонка B)";
                        case APPROVER -> "\"утверждающие\" (колонка C)";
                        case REGISTRAR -> "\"регистраторы\" (колонка D)";
                        case RECEIVER -> "\"получатели\" (колонка E)";
                        default -> throw new IllegalStateException("Unexpected value: " + role);
                    };
                    result.addError("Пользователь " + trimmed + " дублируется в проекте " + projectName + " в колонке " + column);
                }
            }
        }
        return names;
    }
    private List<String> parseNames(String cell, String projectName, Role role) {
        List<String> names = new ArrayList<>();
        if (cell == null || cell.isBlank()) return names;
        for (String part : cell.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (!names.contains(trimmed))
                    names.add(trimmed);
                else {
                    String column = switch (role) {
                        case USER -> "\"пользователи\" (колонка B)";
                        case APPROVER -> "\"утверждающие\" (колонка C)";
                        case REGISTRAR -> "\"регистраторы\" (колонка D)";
                        case RECEIVER -> "\"получатели\" (колонка E)";
                        default -> throw new IllegalStateException("Unexpected value: " + role);
                    };
                    throw new IllegalArgumentException("Пользователь " + trimmed + " дублируется в проекте " + projectName + " в колонке " + column);
                }
            }
        }
        return names;
    }


//    private List<String> emptyFields(Iterator<Row> rows) {
//        List<String> names = new ArrayList<>();
//        while (rows.hasNext()) {
//            Row row = rows.next();
//            for (int i = 0; i < row.getHeight())
//            if (cell == null || cell.isBlank()) return names;
//            for (String part : cell.split(",")) {
//                String trimmed = part.trim();
//                if (!trimmed.isEmpty()) {
//                    names.add(trimmed);
//                }
//            }
//        }
//        return names;
//    }

    // Расстояние Левенштейна (алгоритм сходства строк)
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

    // Ищет самого похожего пользователя в кэше
    private String findClosestMatch(String targetName, List<User> allUsers) {
        String closest = null;
        int minDistance = Integer.MAX_VALUE;
        for (User u : allUsers) {
            if (u.getName() == null) continue;
            String dbName = u.getName().trim();
            int distance = getLevenshteinDistance(targetName.toLowerCase(), dbName.toLowerCase());
            // Если разница не больше 3 букв и имя длиннее 3 символов
            if (distance <= 3 && distance < minDistance && targetName.length() > 3) {
                minDistance = distance;
                closest = dbName;
            }
        }
        return closest;
    }
    private String getPendingFilePath(long chatId) {
        return "pending_import_" + chatId + ".ser";
    }

    // Записываем список проектов в бинарный файл на диск
    private void savePending(long chatId, List<PendingProject> projects) {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(getPendingFilePath(chatId)))) {
            oos.writeObject(projects);
        } catch (IOException e) {
            log.error("savePending failed for chatId={}", chatId, e);
        }
    }

    // Читаем список проектов обратно в память
    @SuppressWarnings("unchecked")
    private List<PendingProject> loadPending(long chatId) {
        File file = new File(getPendingFilePath(chatId));
        if (!file.exists()) return null;
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(file))) {
            return (List<PendingProject>) ois.readObject();
        } catch (Exception e) {
            log.error("loadPending failed for chatId={}", chatId, e);
            return null;
        }
    }

    // Удаляем файл
    private void deletePending(long chatId) {
        new File(getPendingFilePath(chatId)).delete();
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((int) cell.getNumericCellValue());
        return "";
    }


    public static class ProjectSummary {
        public final String name;
        public final int id;
        public final int added;
        public final int notFound;
        public final Role emptyRole; // НОВОЕ: какая роль пуста (null, если всё заполнено)

        ProjectSummary(String name, int id, int added, int notFound, Role emptyRole) {
            this.name = name;
            this.id = id;
            this.added = added;
            this.notFound = notFound;
            this.emptyRole = emptyRole;
        }
    }
}
