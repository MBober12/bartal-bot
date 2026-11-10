package bot.handler;

import bot.keyboard.KeyboardBuilder;
import bot.model.*;
import bot.repository.PaperRepository;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;
import bot.service.ImportResult;
import bot.service.ProjectImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

public class FileHandler {

    private static final Logger log = LoggerFactory.getLogger(TextHandler.class);
    private final PaperRepository paperRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepo;
    private final ProjectImportService importService;
    public FileHandler(PaperRepository paperRepo, UserRepository userRepo, ProjectRepository projectRepo) {
        this.paperRepo = paperRepo;
        this.userRepo = userRepo;
        this.projectRepo = projectRepo;
        this.importService = new ProjectImportService(projectRepo, userRepo);
    }

    public DocResult handlePhoto(long chatId, String fileId) {
        return handleFile(chatId, fileId, true);
    }

    public DocResult handleDocument(long chatId, String fileId, InputStream fileStream) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return TextResult.build(chatId, "Вы не зарегистрированы.");
        }
        User user = userOpt.get();
        UserState state = UserState.fromText(user.getState());
        if (state == UserState.SENDING_PROJECTS_TABLE) {
            return handleProjectTable(chatId, user, fileStream);
        }
        return handleFile(chatId, fileId, false);
    }

    private DocResult handleFile(long chatId, String fileId, boolean isPhoto) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return TextResult.build(chatId, "Вы не зарегистрированы.");
        }
        User user = userOpt.get();
        String state = user.getState() != null ? user.getState() : "";

        if (state.equals(UserState.PASSING_DOC.getText())) {
            String details = user.getPreviousMessage();
            String name = user.getName();

            boolean isAct = details.contains("1.Тип документа - акт") || details.contains("1.Тип документа - прочее");
            DocType docType = isAct ? DocType.ACT : DocType.INVOICE;
            Paper.FileType fileType = isPhoto ? Paper.FileType.PHOTO : Paper.FileType.DOCUMENT;
            String projectName = "";

            int projStart = details.indexOf("2.Объект/проект - ");
            if (projStart != -1) {
                projStart += 18;
                int tildeIndex = details.indexOf("~", projStart);
                int newlineIndex = details.indexOf("\n", projStart);
                int end = (tildeIndex != -1) ? tildeIndex : (newlineIndex != -1 ? newlineIndex : details.length());
                projectName = details.substring(projStart, end).trim();
            }

            // Убираем служебный символ ~, если он был добавлен при наличии доп. инфы
            String newDetails = details.contains("~") ? details.replaceFirst("~ ", " ").replace("~", "") : details;

            int docId = paperRepo.create(docType);

            if (docId == -1) {
                return TextResult.build(chatId, "Ошибка создания документа.");
            }

            Paper paper = new Paper(docId, docType, fileType, newDetails, projectRepo.getProject(ProjectRepository.getTableName(projectName)), fileId);
            paper.setRepo(paperRepo);

            DocResult result = paper.submit(chatId, name);

            // Кнопка "Перестать передавать документы" — state не очищаем, продолжаем принимать
            BotTask hint = new BotTask(BotTask.Type.SEND_TEXT, chatId, "Документ принят. Можете отправить следующий или нажмите \"Перестать передавать документы\".", null, KeyboardBuilder.stopTransferKeyboard());
            result.addTask(hint);

            return result;
        }

        return TextResult.build(chatId, "Сейчас не нужно отправлять документ.");
    }

    private DocResult handleProjectTable(long chatId, User user, InputStream fileStream)
    {
        // try-with-resources автоматически закроет поток и файл
        String path = "temp" + chatId + ".xlsx";
        ImportResult result;
        try (InputStream inputStream = fileStream;
             OutputStream outputStream = new FileOutputStream(path)) {
            inputStream.transferTo(outputStream);
            result = importService.readFromExcel(new File(path));
        } catch (Exception e) {
            log.error("handleProjectTable failed for chatId={}", chatId, e);
            return TextResult.build(chatId, "Ошибка обработки файла проекта. Пожалуйста, проверьте формат и отправьте снова.");
        }

        String text = "";
        DocResult docResult = new DocResult();
        if (result.hasEmptyFields() || result.hasNotFoundUsers()) {
            text = "Тестовый импорт завершен. Невозможно сохранить изменения, т.к найдены ошибки.\n" + result.getSummary() + "\n\nУстраните ошибки и загрузите файл снова(можно загрузить прямо сейчас)";
            return TextResult.build(chatId, text);
        } else {
            text = "Тестовый импорт завершен. Ошибки не найдены, возможно сохранить изменения.\n\nЗагрузить новый список проектов в базу данных?";
        }
        user.setState(UserState.COMMITING);
        userRepo.save(user);
        BotTask task = new BotTask(BotTask.Type.SEND_TEXT, chatId, text, null, KeyboardBuilder.commitKeyboard());
        docResult.addTask(task);
        return docResult;
    }
}
