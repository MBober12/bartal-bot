package bot.handler;

import bot.model.UserState;
import bot.keyboard.KeyboardBuilder;
import bot.model.*;
import bot.repository.PaperRepository;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;
import bot.service.ProjectImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

public class CallbackHandler {
    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);

    private final PaperRepository paperRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepository;

    public CallbackHandler(PaperRepository paperRepo, UserRepository userRepo, ProjectRepository projectRepo) {
        this.paperRepo = paperRepo;
        this.userRepo = userRepo;
        this.projectRepository = projectRepo;
    }

    public DocResult handleCallbackQuery(long chatId, String callbackData, int mesId) {
        // Обработка настроек
        if (callbackData.equals("settings_show") || callbackData.equals("settings_hide")) {
            return handleSettingsCallback(chatId, callbackData, mesId);
        }

        if (callbackData.equals("commit") || callbackData.equals("cancel")) {
            return handleCommitCallback(chatId, callbackData, mesId);
        }
        

        // Парсим callback: "ACCEPTING_176" → Stage.ACCEPTING, docId=176
        int separatorIndex = callbackData.lastIndexOf('_');
        if (separatorIndex == -1) {
            return textResult(chatId, "Некорректная команда");
        }
        String stageName = callbackData.substring(0, separatorIndex);
        int docId;
        Stage targetStage;
        try {
            docId = Integer.parseInt(callbackData.substring(separatorIndex + 1));
            targetStage = Stage.valueOf(stageName);
        } catch (IllegalArgumentException e) {
            log.warn("Неизвестный callback: {}", callbackData);
            return textResult(chatId, "Некорректная команда");
        }

        Optional<Paper> paperOpt = paperRepo.findById(docId);
        if (paperOpt.isEmpty()) {
            return textResult(chatId, "Документ #" + docId + " не найден");
        }
        Paper paper = paperOpt.get();
        paper.setRepo(paperRepo);

        String actorName = userRepo.getName(chatId);

        // Проверяем, допустимо ли это действие для текущей стадии документа
        if (!paper.getAllowedTargets().contains(targetStage)) {
            return textResult(chatId, "Этот документ уже обрабатывается другим пользователем.");
        }

        // Блокировка — запрашиваем причину
        if (targetStage == Stage.BLOCKED_AT_ACCEPTANCE) {
            return askBlockReason(chatId, docId, UserState.ENTERING_APPROVING_BLOCK_REASON, "Введите причину блокировки документа", paper.getStage());
        }
        if (targetStage == Stage.BLOCKED_AT_REGISTRATION) {
            return askBlockReason(chatId, docId, UserState.ENTERING_REGISTRATION_BLOCK_REASON, "Введите причину отказа в регистрации", paper.getStage());
        }
        if (targetStage == Stage.BLOCKED_AT_PAYMENT) {
            String prompt = paper.getDocType() == DocType.INVOICE
                    ? "Введите причину отказа в оплате"
                    : "Введите причину отказа в приемке";
            return askBlockReason(chatId, docId, UserState.ENTERING_PAYMENT_BLOCK_REASON, prompt, paper.getStage());
        }

        // Возвраты — нужен контекст текущей стадии
        if (targetStage == Stage.SUBMITTED && paper.getStage() == Stage.ACCEPTING) {
            return paper.returnToApprovers(chatId, actorName);
        }
        if (targetStage == Stage.ACCEPTED_WITH_REG && paper.getStage() == Stage.REGISTERING) {
            return paper.returnToRegistrars(chatId, actorName);
        }
        if (paper.getStage() == Stage.PAYING && (targetStage == Stage.REGISTERED || targetStage == Stage.ACCEPTED_NO_REG || targetStage == Stage.SUBMITTED)) {
            return paper.returnToReceivers(chatId, actorName);
        }

        // Forward-переходы
        return switch (targetStage) {
            case ACCEPTING -> paper.startApproving(chatId, actorName);
            case ACCEPTED_NO_REG -> paper.approveNoRegistration(chatId, actorName);
            case ACCEPTED_WITH_REG -> paper.approveWithRegistration(chatId, actorName);
            case REGISTERING -> paper.startRegistering(chatId, actorName);
            case REGISTERED -> paper.register(chatId, actorName);
            case PAYING -> paper.startPaying(chatId, actorName);
            case PAID -> paper.pay(chatId, actorName);
            default -> null;
        };
    }

    private DocResult handleSettingsCallback(long chatId, String callbackData, int mesId) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return textResult(chatId, "Пользователь не найден");
        }

        User user = userOpt.get();
        boolean show = callbackData.equals("settings_show");
        user.setShowInfo(show);
        
        userRepo.save(user);

        DocResult result = new DocResult();
        var markup = KeyboardBuilder.settingsKeyboard(show);
        String text = "Выберите желаемый тип отображения информации по документу при вводе данных. Текущий тип - " +
                (show ? "отображать" : "не отображать") + ".";

        BotTask task = new BotTask(BotTask.Type.EDIT_MARKUP, chatId, mesId, text, null, markup);
        result.addTask(task);

        return result;
    }

    private DocResult handleCommitCallback(long chatId, String callbackData, int mesId) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return textResult(chatId, "Пользователь не найден");
        }
        User user = userOpt.get();
        if (user.getState().equals(UserState.COMMITING.toString())) {
            if (callbackData.equals("commit")) {
                ProjectImportService projectImport = new ProjectImportService(projectRepository, userRepo);
                int addedProjects = -1;
                try {
                    addedProjects = projectImport.importFromExcel(new File("temp" + chatId + ".xlsx"));
                } catch (Exception e) {
                    log.error("Ошибка импорта Excel для chatId={}", chatId, e);
                }
                user.setState("");
                userRepo.save(user);
                DocResult result = new DocResult();
                result.addTask(new BotTask(BotTask.Type.EDIT_MARKUP, chatId, mesId, null, null, new Keyboard(true)));
                result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, "Успешно импортировано. Добавлено " + addedProjects + " проектов", null, null));
                return result;
            } else if (callbackData.equals("cancel")) {
                user.setState("");
                userRepo.save(user);
                DocResult result = new DocResult();
                result.addTask(new BotTask(BotTask.Type.EDIT_MARKUP, chatId, mesId, null, null, new Keyboard(true)));
                result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, "Импорт успешно отменен", null, null));
                return result;
            }
        }
        return null;
    }

    private DocResult askBlockReason(long chatId, int docId, UserState state, String prompt, Stage currentStage) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return textResult(chatId, "Пользователь не найден");
        }
        User user = userOpt.get();
        user.setState(state.getText());
        user.setPreviousMessage(docId + ":" + currentStage.name());
        userRepo.save(user);
        return textResult(chatId, prompt);
    }

    private DocResult textResult(long chatId, String text) {
        DocResult result = new DocResult();
        result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, text, null, null));
        return result;
    }
}
