package bot.test;

import bot.model.Role;
import bot.model.User;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;
import bot.service.PendingProject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class ProjectsTest {


    public static File createRandomExcel(int numberOfCollums, UserRepository userRepo) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Проекты");

            // Заголовок
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("проект");
            header.createCell(1).setCellValue("пользователи");
            header.createCell(2).setCellValue("утверждающие");
            header.createCell(3).setCellValue("регистраторы");
            header.createCell(4).setCellValue("получатели");

            int rowNum = 1;
            for (int i = 0; i < numberOfCollums; i++) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(getRandomProject());
                for (int x = 1; x < 5; x++) {
                    row.createCell(x).setCellValue(getListOfNames(userRepo));
                }
            }

            // Автоподбор ширины
            for (int i = 0; i < 5; i++) {
                sheet.autoSizeColumn(i);
            }
            Random random = new Random();
            String filePath = random.nextInt(99999) + "_projects.xlsx";
            try (var fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
            return new File(filePath);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getRandomName(UserRepository userRepo) {
        String[] names = {"Александр", "Алексей", "Иван", "Артем", "Олег", "Михаил", "Илья", "Андрей", "Арсений", "Валерий"};
        String[] surnames = {"Иванов", "Петров", "Сидоров", "Алексеев", "Барыкин", "Кытин", "Барков", "Белоножкин", "Фролов"};
        Random random = new Random();
        StringBuilder randomName = new StringBuilder();
        randomName.append(names[random.nextInt(names.length)]);
        randomName.append(" ");
        randomName.append(surnames[random.nextInt(surnames.length)]);
        User user = new User(random.nextInt(99999999), randomName.toString(), Role.USER);
        userRepo.save(user); // добавляем ррандомных юзеров в репо
        return randomName.toString();
    }

    private static String getRandomProject() {
        String[] first = {"Проект", "Стройка", "Сарай", "Дом", "Дача", "Подстанция", "Надстанция", "Подвал", "Чердак", "Домик", "Срочно", "Не срочно", "Корпоратив", "Важно", "Неважно"};
        String[] second = {"Мск", "Москва", "Рязань", "Рыбное", "Ростов", "Магадан", "Сыктывкар", "Вологда", "Сургут", "Воркута", "Волгоград", "Луна", "Краснодар", "Астрахань", "Петропавловск"};
        Random random = new Random();
        StringBuilder randomName = new StringBuilder();
        randomName.append(first[random.nextInt(first.length)]);
        randomName.append(" ");
        randomName.append(second[random.nextInt(second.length)]);
        randomName.append(random.nextInt(1000));
        return randomName.toString();
    }

    private static String getListOfNames(UserRepository userRepo) {
        Random random = new Random();
        int numberOfUsers = random.nextInt(5) + 1;
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < numberOfUsers; i++) {
            output.append(getRandomName(userRepo));
            if (i + 1 < numberOfUsers) {
                output.append(", ");
            }
        }
        return output.toString();
    }

    public static void compareProjects(File file, ProjectRepository projectRepo, UserRepository userRepo) throws IOException {
        List<User> allUsers = userRepo.findAll();
        Map<String, Long> nameToChatId = new HashMap<>();
        for (User u : allUsers) {
            if (u.getName() != null) nameToChatId.put(u.getName().trim(), u.getChatId());
        }

        List<PendingProject> projectsToSave = new ArrayList<>();
        int projectId = 1;
        int numberOfErrors = 0;
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (rows.hasNext()) rows.next();

            while (rows.hasNext()) {
                Row row = rows.next();
                String projectName = getCellString(row, 0).trim();
                if (projectName.isEmpty()) continue;

                List<String> userNames = parseNames(getCellString(row, 1));
                List<String> approverNames = parseNames(getCellString(row, 2));
                List<String> registrarNames = parseNames(getCellString(row, 3));
                List<String> receiverNames = parseNames(getCellString(row, 4));

               List<User> usersOfProject = projectRepo.getUsersForProject(projectRepo.getTableName(projectName));

               int numberOfEqualRoles = 0;
               int actualNumberOfEqualRoles = userNames.size() + approverNames.size() + registrarNames.size() + receiverNames.size();

               for (User user : usersOfProject) {
                   if (user.hasRole(Role.USER)) {
                       for (String name : userNames) {
                           if (user.getName().equals(name)) {
                               numberOfEqualRoles++;
                           }
                       }
                   }
                   if (user.hasRole(Role.APPROVER)) {
                       for (String name : approverNames) {
                           if (user.getName().equals(name)) {
                               numberOfEqualRoles++;
                           }
                       }
                   }
                   if (user.hasRole(Role.REGISTRAR)) {
                       for (String name : registrarNames) {
                           if (user.getName().equals(name)) {
                               numberOfEqualRoles++;
                           }
                       }
                   }
                   if (user.hasRole(Role.RECEIVER)) {
                       for (String name : receiverNames) {
                           if (user.getName().equals(name)) {
                               numberOfEqualRoles++;
                           }
                       }
                   }
               }
               if (numberOfEqualRoles == actualNumberOfEqualRoles)
                   System.out.println("Проект " + projectName + " - соответствует число пользователей.\n" + numberOfEqualRoles + " == " + actualNumberOfEqualRoles);
               else {
                   System.out.println("Проект " + projectName + " - не соответствует число пользователей.\n" + numberOfEqualRoles + " != " + actualNumberOfEqualRoles);
                   numberOfErrors++;
               }
            }
        }
        System.out.println("Несоответствий: " + numberOfErrors);
    }

    private static List<String> parseNames(String cell) {
        List<String> names = new ArrayList<>();
        if (cell == null || cell.isBlank()) return names;
        for (String part : cell.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }
    private static String getCellString(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((int) cell.getNumericCellValue());
        return "";
    }
}

