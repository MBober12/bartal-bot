package bot.handler;

import bot.keyboard.KeyboardBuilder;
import bot.model.*;
import bot.service.ProjectImportService;
import bot.repository.PaperRepository;
import bot.repository.ProjectRepository;
import bot.repository.UserRepository;
import bot.repository.ReminderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TextHandler {
    private static final Logger log = LoggerFactory.getLogger(TextHandler.class);
    private final PaperRepository paperRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepo;
    private final ProjectImportService importService;
    private final BirthdayHandler birthdayHandler;
    private final ReminderRepository reminderRepo;

    public TextHandler(PaperRepository paperRepo, UserRepository userRepo, ProjectRepository projectRepo) {
        this(paperRepo, userRepo, projectRepo, null);
    }

    public TextHandler(PaperRepository paperRepo, UserRepository userRepo, ProjectRepository projectRepo, ReminderRepository reminderRepo) {
        this.paperRepo = paperRepo;
        this.userRepo = userRepo;
        this.projectRepo = projectRepo;
        this.importService = new ProjectImportService(projectRepo, userRepo);
        this.birthdayHandler = new BirthdayHandler();
        this.reminderRepo = reminderRepo;
    }

    public DocResult handleText(long chatId, String text) {
        Optional<User> userOpt = userRepo.findById(chatId);

        if (userOpt.isEmpty()) {
            return TextResult.build(chatId, "Ваш айди отсутствует в списке пользователей.\nОбратитесь к администратору для получения доступа и сообщите ему код: " + chatId);
        }

        User user = userOpt.get();
        String state = user.getState() != null ? user.getState() : "";

        if (text.equals("/mainmenu")) {
            DocResult bd = birthdayHandler.check(chatId);
            if (bd != null) return bd;
        }

        if (isMenuCommand(text) && !state.isEmpty()) {
            user.setState(UserState.EMPTY_STATE);
            userRepo.save(user);
            state = "";
        }

        if (!state.isEmpty()) {
            DocResult stateResult = handleState(chatId, user, text, state);
            if (stateResult != null) return stateResult;
        }

        return handleCommand(chatId, user, text);
    }

    // === Сброс состояния ===

    private boolean isMenuCommand(String text) {
        return text.equals("/start") || text.equals("/mainmenu") || text.equals("Вернуться в главное меню")
                || text.equals("Передать документы") || text.equals("Просмотреть информацию о документах")
                || text.equals("Управление проектами")
                || text.equals("Настройки") || text.equals("Просмотреть полную информацию по конкретному документу")
                || text.equals("Просмотреть последние 30 переданных документов") || text.equals("Назад")
                || text.equals("Просмотреть документы, ожидающие меня")
                || text.equals("Просмотреть информацию о переданных документах")
                || text.equals("Просмотреть последние 15 переданных документов")
                || text.equals("Изменить cписoк пользователей бота")
                || text.equals("Изменить список пользователей") || text.equals("Изменить список администраторов") || text.equals("Изменить имя пользователя") || text.equals("Изменить имя администратора")
                || text.startsWith("Добавить ") || text.startsWith("Удалить ")
                || text.startsWith("Просмотреть список псевдонимов ");
    }

    // === Обработка состояний ===
    private DocResult handleState(long chatId, User user, String text, String stateStr) {
        UserState state = UserState.fromText(stateStr);
        if (state == null) {
            user.setState(UserState.EMPTY_STATE);
            userRepo.save(user);
            return null;
        }

        switch (state) {
            case ADDING_USER -> { return addUserStep1(chatId, user, text, Role.USER, UserState.ADDING_USER_ALIAS); }
            case ADDING_ADMIN -> { return addUserStep1(chatId, user, text, Role.ADMIN, UserState.ADDING_ADMIN_ALIAS); }

            case ADDING_USER_ALIAS -> { return addUserStep2(chatId, user, text, Role.USER); }
            case ADDING_ADMIN_ALIAS -> { return addUserStep2(chatId, user, text, Role.ADMIN); }

            case REMOVE_USER -> { return removeUserStep(chatId, user, text, Role.USER); }
            case REMOVING_ADMIN -> { return removeUserStep(chatId, user, text, Role.ADMIN); }

            case CHANGING_USER_ID -> { return changeUserNameStep1(chatId, user, text); }
            case CHANGING_USER_NAME -> { return changeUserNameStep2(chatId, user, text); }

            case ENTERING_PROJECT_NAME -> { return enterProjectName(text, chatId, user); }

            case ENTERIND_DOC_ID -> { return enterDocId(text, chatId, user); }

            case ENTERING_DOCUMENT_TYPE -> {
                String type;
                if (text.equalsIgnoreCase("Акт")) type = "акт";
                else if (text.equalsIgnoreCase("Счет на оплату")) type = "счет на оплату";
                else if (text.equalsIgnoreCase("Прочее")) type = "прочее";
                else return TextResult.build(chatId, "Выберите: Акт, Счет на оплату или Прочее");

                boolean show = user.getShowInfo();
                user.setState(UserState.ENTERING_DOC_PROJECT_NAME);
                user.setPreviousMessage("1.Тип документа - " + type);
                userRepo.save(user);
                String prefix = "";
                if (user.getShowInfo() == true)
                    prefix = "1.Тип документа - " + type + "\n2.1";//TODO: раньше по логике после проекта надо написать в скобках доп инфу, щас надо переделать
                return TextResult.build(chatId, prefix + "К какому объекту/проекту относится передаваемый документ? Нажмите на кнопку и начните писать имя проекта, а затем выберите из списка, нажав на него.", KeyboardBuilder.enterInlineMode());
            }

            case ENTERING_DOC_PROJECT_NAME -> {
                boolean hasAccess = false;
                for (Project project : projectRepo.getProjectsForUser(user)) {
                    if (project.getProjectName().equalsIgnoreCase(text)) {
                        hasAccess = true;
                        break;
                    }
                }
                if (hasAccess) {
                    user.setState(UserState.ENTERING_EXTRA_INFO);
                    user.setPreviousMessage(user.getPreviousMessage() + "\n2.Объект/проект - " + text);
                    userRepo.save(user);
                    String prefix = "";
                    if (user.getShowInfo() == true)
                        prefix = user.getPreviousMessage() + "\n2.2.";
                    return TextResult.build(chatId, prefix + "Укажите дополнительную информацию, если требуется. Если нет - нажмите на кнопку.", KeyboardBuilder.extraInformationKeyboard());
                } else {
                    return TextResult.build(chatId, "У вас нет доступа к этому проекту или его не существует, выберите проект из списка.", KeyboardBuilder.enterInlineMode());
                }
            }

            case ENTERING_EXTRA_INFO -> {
                user.setState(UserState.ENTERING_RESPONSIBLE_PERSON);
                if (!text.equals("Дополнительная информация не требуется"))
                    user.setPreviousMessage(user.getPreviousMessage() + "~ (" + text + ")");
                else
                    user.setPreviousMessage(user.getPreviousMessage() + "~");

                userRepo.save(user);
                String prefix = "";
                if (user.getShowInfo() == true)
                    prefix = user.getPreviousMessage() + "\n3.";
                return TextResult.build(chatId, prefix + "Укажите ответственного (фамилия, имя) за передачу оригиналов документов в бухгалтерию ГК \"Бартал\" (введите с клавиатуры)", KeyboardBuilder.removeKeyboard());
            }
            case ENTERING_RESPONSIBLE_PERSON -> {
                user.setState(UserState.ENTERING_RECEIPT);
                user.setPreviousMessage(user.getPreviousMessage() + "\n3.Ответственный - " + text);
                userRepo.save(user);
                String prefix = "";
                if (user.getShowInfo() == true)
                    prefix = user.getPreviousMessage() + "\n4.";
                return TextResult.build(chatId, prefix + "Укажите сумму платежа (введите с клавиатуры)", KeyboardBuilder.removeKeyboard());
            }

            case ENTERING_RECEIPT -> {
                user.setState(UserState.ENTERING_CONTRACTOR);
                user.setPreviousMessage(user.getPreviousMessage() + "\n4.Сумма платежа - " + text);
                userRepo.save(user);
                String prefix = "";
                if (user.getShowInfo() == true)
                    prefix = user.getPreviousMessage() + "\n5.";
                return TextResult.build(chatId, prefix + "Укажите контрагента-получателя платежа (введите с клавиатуры)", KeyboardBuilder.removeKeyboard());
            }

            case ENTERING_CONTRACTOR -> {
                user.setState(UserState.PASSING_DOC);
                user.setPreviousMessage(user.getPreviousMessage() + "\n5.Контрагент-получатель - " + text);
                userRepo.save(user);
                String prefix = "";
                if (user.getShowInfo() == true)
                    prefix = user.getPreviousMessage() + "\n\n";
                return TextResult.build(chatId, prefix + "Пришлите документ(ы) для отправки. Бот будет передавать любые фото/файлы, которые вы отправите до тех пор, пока не будет нажата кнопка \"Перестать передавать документы\"", KeyboardBuilder.stopTransferKeyboard());
            }

            case ENTERING_APPROVING_BLOCK_REASON, ENTERING_REGISTRATION_BLOCK_REASON, ENTERING_PAYMENT_BLOCK_REASON -> {
                return handleBlockReason(chatId, user, text, state);
            }
        }

        return null;
    }

    // === Команды меню ===

    private DocResult handleCommand(long chatId, User user, String text) {

        if (user.hasRole(Role.ADMIN)) {
            switch (text) {
                case "Управление проектами" -> { return handleProjectMenu(chatId, user); }
                case "Просмотреть полную информацию по конкретному проекту" -> { return handleEnterProjectName(chatId, user); }
                case "Изменить cписoк пользователей бота" -> { return handleChangeUserListMenu(chatId); }
                case "Изменить список пользователей" -> { return handleChangeRoleMenu(chatId, "пользователей", Role.USER); }
                case "Изменить список администраторов" -> { return handleChangeRoleMenu(chatId, "администраторов", Role.ADMIN); }
                case "Изменить имя пользователя" -> { return startChangeUserName(chatId, user, Role.USER); }
                case "Изменить имя администратора" -> { return startChangeUserName(chatId, user, Role.ADMIN); }
                case "Добавить пользователя" -> { return startAddUser(chatId, user, Role.USER, UserState.ADDING_USER); }
                case "Удалить пользователя" -> { return startRemoveUser(chatId, user, Role.USER, UserState.REMOVE_USER); }
                case "Добавить администратора" -> { return startAddUser(chatId, user, Role.ADMIN, UserState.ADDING_ADMIN); }
                case "Удалить администратора" -> { return startRemoveUser(chatId, user, Role.ADMIN, UserState.REMOVING_ADMIN); }
                case "Просмотреть список псевдонимов пользователей" -> { return viewUserList(chatId, Role.USER); }
                case "Просмотреть список псевдонимов администраторов" -> { return viewUserList(chatId, Role.ADMIN); }
                case "Просмотреть последние 30 переданных документов" -> { return handleViewLast30(chatId); }
                case "Просмотреть информацию о документах" -> { return handleViewDocs(chatId, user); }
                case "Назад" -> { return handleChangeUserListMenu(chatId); }
            }
        }
        if (user.hasRole(Role.USER)) {
            switch (text) {
                case "Передать документы" -> { return handleTransmitDoc(chatId, user); }
                case "Перестать передавать документы" -> { return handleStopTransmitDoc(chatId, user); }
                case "Просмотреть последние 15 переданных вами документов" -> { return handleViewLast15(chatId, user); }
                case "Просмотреть полную информацию по конкретному документу" -> { return handleEnterDocId(chatId, user); }
                case "Настройки" -> { return handleSettings(chatId, user); }
                case "Просмотреть информацию о переданных документах" -> { return handleViewDocs(chatId, user); }
            }
        }
        switch (text) {
            case "/start" -> { return handleStart(chatId); }
            case "/mainmenu", "Вернуться в главное меню" -> { return handleMainMenu(chatId, user); }
            case "Просмотреть документы, ожидающие меня" -> { return handlePendingDocs(chatId, user); }
        }
        return null;
    }

    // === Главное меню ===

    private DocResult handleStart(long chatId) {
        Optional<User> userOpt = userRepo.findById(chatId);
        if (userOpt.isEmpty()) {
            return TextResult.build(chatId, "Ваш юзернейм отсутствует в списке пользователей.\nОбратитесь к администратору для получения доступа и сообщите ему код: " + chatId);
        }
        return handleMainMenu(chatId, userOpt.get());
    }

    private DocResult handleMainMenu(long chatId, User user) {
        Keyboard markup = KeyboardBuilder.mainMenuKeyboard(user);

        String textList = getListOfRoles(user);
        if (textList.length() > 14) //максимальная длина списка, если в нем один элемент, соответственно в списке несколько ролей
             return TextResult.build(chatId, user.getName() + ", вы в главном меню. Ваши роли -" + textList + ". Вы можете воспользоваться кнопками меню.", markup);
        else {
            return TextResult.build(chatId, user.getName() + ", вы в главном меню. Ваша роль -" + textList + ". Вы можете воспользоваться кнопками меню.", markup);
        }
    }

    private String getListOfRoles(User user) {
        StringBuilder textList = new StringBuilder();
        if (user.hasRole(Role.ADMIN))
            textList.append(" ").append("администратор").append(",");
        if (user.hasRole(Role.USER))
            textList.append(" ").append("пользователь").append(",");
        if (user.hasRole(Role.APPROVER))
            textList.append(" ").append("согласующий").append(",");
        if (user.hasRole(Role.REGISTRAR))
            textList.append(" ").append("регистратор").append(",");
        if (user.hasRole(Role.RECEIVER))
            textList.append(" ").append("получатель").append(",");

        textList.deleteCharAt(textList.length() - 1); // удаляем запятую последним символом

        return textList.toString();
    }

    // === Передача документов ===

    private DocResult handleTransmitDoc(long chatId, User user) {
        if (!projectRepo.findAllActive().isEmpty()) {
            if (!projectRepo.getProjectsForUserAndRole(user, Role.USER).isEmpty()) {
                boolean show = user.getShowInfo();
                user.setState(show ? UserState.ENTERING_DOCUMENT_TYPE : UserState.ENTERING_DOCUMENT_TYPE);
                user.setPreviousMessage("");
                userRepo.save(user);

                return TextResult.build(chatId, "Для передачи документа сначала заполните дополнительную информацию по документу.\nКакой документ хотите передать?", KeyboardBuilder.docTypeKeyboard());
            } else {
                return TextResult.build(chatId, "Невозможно выполнить действие, тк вы не добавлены в качестве пользователя ни для одного проекта. Обратитесь к администратору.", KeyboardBuilder.docTypeKeyboard());
            }
        } else
            return TextResult.build(chatId, "Невозможно выполнить действие, тк ни одного проекта еще не добавлено. Обратитесь к администратору", KeyboardBuilder.docTypeKeyboard());
    }

    // === Остановка передачи документов ===

    private DocResult handleStopTransmitDoc(long chatId, User user) {
        user.setState(UserState.EMPTY_STATE);
        userRepo.save(user);
        return TextResult.build(chatId, "Передача документов завершена.", KeyboardBuilder.removeKeyboard());
    }

    // === Просмотр документов ===

    private DocResult handlePendingDocs(long chatId, User user) {
        java.util.List<Paper> allPapers = paperRepo.findUnpaid();
        DocResult result = new DocResult();

        for (Paper paper : allPapers) {
            paper.setRepo(paperRepo);
            Stage stage = paper.getStage();
            boolean match = false;

            if (user.hasRole(Role.APPROVER)) {
                if (stage == Stage.SUBMITTED && paper.getDocType() == DocType.INVOICE && paper.getAcceptorId() == 0)
                    match = true;
                if (stage == Stage.ACCEPTING && paper.getAcceptorId() == chatId)
                    match = true;
            }
            if (user.hasRole(Role.REGISTRAR)) {
                if (stage == Stage.ACCEPTED_WITH_REG && paper.getRegistrarId() == 0)
                    match = true;
                if (stage == Stage.REGISTERING && paper.getRegistrarId() == chatId)
                    match = true;
            }
            if (user.hasRole(Role.RECEIVER)) {
                if (stage == Stage.ACCEPTED_NO_REG && paper.getReceiverId() == 0)
                    match = true;
                if (stage == Stage.REGISTERED && paper.getReceiverId() == 0)
                    match = true;
                if (stage == Stage.SUBMITTED && paper.getDocType() == DocType.ACT && paper.getReceiverId() == 0)
                    match = true;
                if (stage == Stage.PAYING && paper.getReceiverId() == chatId)
                    match = true;
            }

            if (match) {
                // Админ — простой список без reply
                if (user.hasRole(Role.ADMIN)) {
                    result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, "DocId " + paper.getDocId(), null, null));
                } else {
                    // Обычный пользователь — reply на оригинальное сообщение
                    int originalMesId = getOriginalMessageId(paper, chatId, user);
                    BotTask task = new BotTask(BotTask.Type.SEND_TEXT, chatId, "DocId " + paper.getDocId(), null, null);
                    if (originalMesId > 0) {
                        task.setReplyToMessageId(originalMesId);
                    }
                    result.addTask(task);
                }
            }
        }

        if (result.getTasks().isEmpty()) {
            return TextResult.build(chatId, "Пока нет документов, ожидающих вашего действия.");
        }
        return result;
    }

    private int getOriginalMessageId(Paper paper, long chatId, User user) {
        if (user.hasRole(Role.APPROVER) && (paper.getStage().equals(Stage.SUBMITTED) || (paper.getStage().equals(Stage.ACCEPTING) && paper.getAcceptorId() == chatId))) {
            HashMap<Long, Integer> map = paper.getApprovers();
            if (map != null && map.containsKey(chatId)) return map.get(chatId);
        }
        if (user.hasRole(Role.REGISTRAR) && (paper.getStage().equals(Stage.ACCEPTED_WITH_REG) || (paper.getStage().equals(Stage.REGISTERING) && paper.getRegistrarId() == chatId))) {
            HashMap<Long, Integer> map = paper.getRegistrars();
            if (map != null && map.containsKey(chatId)) return map.get(chatId);
        }
        if (user.hasRole(Role.RECEIVER) && ((paper.getStage().equals(Stage.SUBMITTED) && paper.getDocType().equals(DocType.ACT)) || paper.getStage().equals(Stage.ACCEPTED_NO_REG) || paper.getStage().equals(Stage.REGISTERED) || (paper.getStage().equals(Stage.PAYING) && paper.getReceiverId() == chatId))) {
            HashMap<Long, Integer> map = paper.getReceivers();
            if (map != null && map.containsKey(chatId)) return map.get(chatId);
        }
        return 0;
    }

    private DocResult handleViewDocs(long chatId, User user) {
        if (user.hasRole(Role.ADMIN) || user.hasRole(Role.USER)) {
            return TextResult.build(chatId, "Выберите, какую информацию вы хотите получить.", KeyboardBuilder.viewDocsMenuKeyboard(user));
        } else {
            return TextResult.build(chatId, "Эта команда недоступна вам.");
        }
    }

    private DocResult handleViewLast15(long chatId, User user) {
        List<Paper> papers;

        papers = paperRepo.findByUser(chatId);
        papers.sort(Comparator.comparingInt(Paper::getDocId));
        if (papers.isEmpty()) {
            return TextResult.build(chatId, "Нет переданных документов.", KeyboardBuilder.docDetailsWithBackKeyboard());
        }
        int count = Math.min(papers.size(), 15);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Paper p = papers.get(i);
            p.setRepo(paperRepo);
            sb.append(p.getStatusSummary(userRepo)).append("\n");
        }
        return TextResult.build(chatId, sb.toString(), KeyboardBuilder.docDetailsWithBackKeyboard());
    }

    private DocResult handleViewLast30(long chatId) {
        List<Paper> papers = paperRepo.findRecent(30);
        papers.sort(Comparator.comparingInt(Paper::getDocId));
        if (papers.isEmpty()) {
            return TextResult.build(chatId, "Нет документов.", KeyboardBuilder.docDetailsWithBackKeyboard());
        }
        StringBuilder sb = new StringBuilder();
        for (Paper p : papers) {
            p.setRepo(paperRepo);
            sb.append(p.getStatusSummary(userRepo)).append("\n");
        }
        return TextResult.build(chatId, sb.toString(), KeyboardBuilder.docDetailsWithBackKeyboard());
    }

    private DocResult handleEnterDocId(long chatId, User user) {
        user.setState(UserState.ENTERIND_DOC_ID);
        userRepo.save(user);
        return TextResult.build(chatId, "Введите DocId документа:");
    }

    private DocResult enterDocId(String text, long chatId, User user) {
        if (!text.matches("\\d{1,10}")) {
            return TextResult.build(chatId, "Это не похоже на DocId. Он должен состоять только из цифр. Введите DocId еще раз");
        }
        int requestedDocId = Integer.parseInt(text);
        Optional<Paper> paperOpt = paperRepo.findById(requestedDocId);
        if (paperOpt.isEmpty()) {
            return TextResult.build(chatId, "По этому DocId не найдено никакой информации.");
        }
        Paper paper = paperOpt.get();
        paper.setRepo(paperRepo);

        // Проверка доступа: admin или владелец документа
        if (!user.hasRole(Role.ADMIN) && paper.getUserId() != chatId) {
            return TextResult.build(chatId, "Доступ запрещен. Документ с этим docId передан не вами. Введите другой DocId");
        }

        String info = paper.getFullInfo(userRepo);
        user.setState(UserState.EMPTY_STATE);
        userRepo.save(user);

        // Отправляем файл с caption
        BotTask.Type sendType = paper.getFileType() == Paper.FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;
        DocResult result = new DocResult();
        result.addTask(new BotTask(sendType, chatId, info, paper.getFileId(), null));
        return result;
    }

    private DocResult handleProjectMenu(long chatId, User user) {
        var projects = projectRepo.findAllActive();
        DocResult result = new DocResult();
        String filePath = importService.exportToExcel();
        if (projects.isEmpty()) {
            BotTask task = new BotTask(BotTask.Type.SEND_DOCUMENT, chatId,
                    "Проектов пока нет. Вот текущая таблица - заполните и загрузите обратно. \n\n" +
                            "Имена пользователей через запятую с пробелом, например:" +
                            "\nИванов Иван, Петров Петр. " +
                            "\nЗапрещено использовать одинаковые имена проектов. В противном случае импорт таблицы проектов будет невозможен." +
                            "\nТакже просьба не использовать в названиях проектов символ ~ (тильда) и перенос строки. В противном случае они будут убраны из названий проектов. ",
                    null, KeyboardBuilder.projectDetailsWithBackKeyboard());
            if (filePath != null) task.setFilePath(filePath);
            result.addTask(task);
        } else {
            BotTask task = new BotTask(BotTask.Type.SEND_DOCUMENT, chatId,
                    "Сейчас активно " + projects.size() + " проектов. Вот текущая таблица - заполните и загрузите обратно. \n\n" +
                            "Имена пользователей через запятую с пробелом, например:" +
                            "\nИванов Иван, Петров Петр. " +
                            "\nЗапрещено использовать одинаковые имена проектов. В противном случае импорт таблицы проектов будет невозможен." +
                            "\nТакже просьба не использовать в названиях проектов символ ~ (тильда) и перенос строки. В противном случае они будут убраны из названий проектов. ",
                    null, KeyboardBuilder.projectDetailsWithBackKeyboard());
            if (filePath != null) task.setFilePath(filePath);
            result.addTask(task);
        }
        user.setState(UserState.SENDING_PROJECTS_TABLE);
        userRepo.save(user);
        return result;
    }

    private DocResult handleEnterProjectName(long chatId, User user) {
        if (projectRepo.findAll().isEmpty())
            return TextResult.build(chatId, "Проектов пока нет.", KeyboardBuilder.mainMenuKeyboard(user));
        user.setState(UserState.ENTERING_PROJECT_NAME);
        userRepo.save(user);
        return TextResult.build(chatId, "Выберите проект из списка, нажав кнопку кнопку под сообщением.", KeyboardBuilder.enterInlineMode());
    }

    private DocResult enterProjectName(String text, long chatId, User user) {
        Project project = projectRepo.getProject(ProjectRepository.getTableName(text));
        if (project.getId() == -1)
            return TextResult.build(chatId, "Проект с таким именем не найден.");
        String info = project.getFullInfo(userRepo);
        return TextResult.build(chatId, info);
    }

    // === Настройки ===

    private DocResult handleSettings(long chatId, User user) {
        DocResult result = new DocResult();
        boolean show = user.getShowInfo();

        BotTask task = new BotTask(BotTask.Type.SEND_TEXT, chatId,
                "Выберите желаемый тип отображения информации по документу при вводе данных. Текущий тип - " +
                        (show ? "отображать" : "не отображать") + ".",
                null, KeyboardBuilder.settingsKeyboard(show));
        result.addTask(task);

        return result;
    }

    // === Управление пользователями ===

    private DocResult handleChangeUserListMenu(long chatId) {
        return TextResult.build(chatId, "Для изменения списка пользователей бота, воспользуйтесь кнопками.", KeyboardBuilder.changeRoleMenuKeyboard());
    }

    private DocResult handleChangeRoleMenu(long chatId, String singular, Role role) {
        Keyboard markup = switch (role) {
            case USER -> KeyboardBuilder.changeUserListKeyboard();
            case ADMIN -> KeyboardBuilder.changeAdminListKeyboard();

            default -> throw new IllegalStateException("Unexpected value: " + role);
        };
        return TextResult.build(chatId, "Вы хотите добавить или удалить " + singular + " из списка?", markup);
    }

    private DocResult startAddUser(long chatId, User user, Role role, UserState state) {
        user.setState(state);
        userRepo.save(user);
        String list = userRepo.getTextUserList(role);
        String singular = getSingularRoleNameGenitive(role);
        String plural = getRoleName(role);
        String header = "Для добавления нового " + singular + " напишите его ID, который он должен получить в боте если напишет /start.\nТекущий список " + plural + ":";
        return splitAndSendResult(chatId, header, list, new Keyboard(true));
    }

    private DocResult startRemoveUser(long chatId, User user, Role role, UserState state) {
        user.setState(state);
        userRepo.save(user);
        String list = userRepo.getTextUserList(role);
        String singular = getSingularRoleNameGenitive(role);
        String plural = getRoleName(role);
        String header = "Для удаления " + singular + " напишите его ID.\nТекущий список " + plural + ":";
        return splitAndSendResult(chatId, header, list, new Keyboard(true));
    }

    private DocResult viewUserList(long chatId, Role role) {
        String list = userRepo.getTextUserList(role);
        String roleName = getRoleName(role);
        String header = "Текущий список " + roleName + " и их псевдонимов:";
        return splitAndSendResult(chatId, header, list, new Keyboard(true));
    }

    private DocResult addUserStep1(long chatId, User user, String text, Role role, UserState aliasState) {
        String singular = getSingularRoleNameGenitive(role);
        if (!text.matches("\\d{1,10}")) {
            return TextResult.build(chatId, "Это не похоже на ID. Он должен состоять только из цифр. Введите ID " + singular);
        }
        long targetId = Long.parseLong(text);
        Optional<User> targetOpt = userRepo.findById(targetId);
        if (targetOpt.isPresent()) {
            User target = targetOpt.get();
            if (target.hasRole(role)) {
                user.setState(UserState.EMPTY_STATE);
                userRepo.save(user);
                String roleName = getSingularRoleName(role);
                return TextResult.build(chatId, target.getName() + " уже имеет роль  \"" + roleName + "\"", KeyboardBuilder.changeRoleMenuKeyboard());
            }
            userRepo.addRole(targetId, role);
            if (targetId == chatId) {
                user.addRole(role);
            }
            user.setState(UserState.EMPTY_STATE);
            userRepo.save(user);
            String roleName = getSingularRoleName(role);
            return TextResult.build(chatId, roleName.substring(0, 1).toUpperCase() + roleName.substring(1) + " добавлен.", KeyboardBuilder.changeRoleMenuKeyboard());
        }
        user.setPreviousMessage(String.valueOf(targetId));
        user.setState(aliasState);
        userRepo.save(user);
        return TextResult.build(chatId, "Введите имя/псевдоним для " + singular + ":");
    }

    private DocResult removeUserStep(long chatId, User user, String text, Role role) {
        String singular = getSingularRoleNameGenitive(role);
        if (!text.matches("\\d{1,10}")) {
            return TextResult.build(chatId, "Это не похоже на ID. Он должен состоять только из цифр. Введите ID " + singular);
        }
        long targetId = Long.parseLong(text);
        if (userRepo.findById(targetId).isEmpty()) {
            return TextResult.build(chatId, singular.substring(0, 1).toUpperCase() + singular.substring(1) + " с таким ID не найден.", KeyboardBuilder.changeRoleMenuKeyboard());
        }
        if (userRepo.countByRole(role) <= 1) {
            String roleName = getSingularRoleName(role);
            return TextResult.build(chatId, "Ошибка! Невозможно удалить. В списке остался всего 1 " + roleName + "! Сначала добавьте еще одного.", KeyboardBuilder.changeRoleMenuKeyboard());
        }
        userRepo.removeRole(targetId, role);
        if (targetId == chatId) {
            user.removeRole(role);
        }

        // Удаляем роль/пользователя из всех проектов
        projectRepo.removeRoleFromAllProjects(targetId, role);

        // Проверяем, остались ли другие роли у пользователя
        Optional<User> targetUserOpt = userRepo.findById(targetId);
        if (targetUserOpt.isPresent()) {
            User targetUser = targetUserOpt.get();
            if (targetUser.getRoles().isEmpty()) {
                // Если не осталось никаких ролей - удаляем его изо всех проектов полностью
                projectRepo.removeUserFromAllProjects(targetId);
                if (reminderRepo != null) {
                    reminderRepo.deleteByChatId(targetId);
                }
            }
        } else {
            projectRepo.removeUserFromAllProjects(targetId);
            if (reminderRepo != null) {
                reminderRepo.deleteByChatId(targetId);
            }
        }

        user.setState(UserState.EMPTY_STATE);
        userRepo.save(user);
        String roleName = getSingularRoleName(role);
        return TextResult.build(chatId, roleName.substring(0, 1).toUpperCase() + roleName.substring(1) + " удален.", KeyboardBuilder.changeRoleMenuKeyboard());
    }

    private DocResult addUserStep2(long chatId, User user, String text, Role role) {
        long targetId = Long.parseLong(user.getPreviousMessage());
        Optional<User> targetOpt = userRepo.findById(targetId);
        if (targetOpt.isPresent()) {
            User target = targetOpt.get();
            target.setName(text);
            target.addRole(role);
            userRepo.save(target);
        } else {
            User newUser = new User(targetId, text, role);
            userRepo.save(newUser);
        }
        user.setState(UserState.EMPTY_STATE);
        userRepo.save(user);
        String singular = getSingularRoleName(role);
        return TextResult.build(chatId, singular.substring(0, 1).toUpperCase() + singular.substring(1) + " добавлен.", KeyboardBuilder.changeRoleMenuKeyboard());
    }

    private DocResult startChangeUserName(long chatId, User user, Role targetRole) {
        user.setState(UserState.CHANGING_USER_ID);
        user.setPreviousMessage(targetRole.name());
        userRepo.save(user);
        StringBuilder sb = new StringBuilder();
        List<User> list = (targetRole == Role.ADMIN) ? userRepo.findByRole(Role.ADMIN) : userRepo.findAll();
        for (User u : list) {
            sb.append(u.getChatId()).append(" -- ").append(u.getName()).append("\n");
        }
        String label = (targetRole == Role.ADMIN) ? "администратора" : "пользователя";
        String header = "Для изменения имени " + label + " напишите его ID.\nТекущий список:";
        return splitAndSendResult(chatId, header, sb.toString(), new Keyboard(true));
    }

    private DocResult changeUserNameStep1(long chatId, User user, String text) {
        if (!text.matches("\\d{1,10}")) {
            return TextResult.build(chatId, "ID должен состоять только из цифр. Введите ID:");
        }
        long targetId = Long.parseLong(text);
        Optional<User> targetOpt = userRepo.findById(targetId);
        if (targetOpt.isEmpty()) {
            return TextResult.build(chatId, "Пользователь с ID " + targetId + " не найден. Введите корректный ID:");
        }
        String prevRole = user.getPreviousMessage();
        user.setPreviousMessage(targetId + ":" + (prevRole != null ? prevRole : "USER"));
        user.setState(UserState.CHANGING_USER_NAME);
        userRepo.save(user);
        return TextResult.build(chatId, "Текущее имя: " + targetOpt.get().getName() + ".\nВведите новое имя/псевдоним:");
    }

    private DocResult changeUserNameStep2(long chatId, User user, String text) {
        String newName = text.trim();
        if (newName.isEmpty()) {
            return TextResult.build(chatId, "Имя не может быть пустым. Введите новое имя:");
        }
        String prev = user.getPreviousMessage();
        long targetId;
        String roleStr = "USER";
        if (prev != null && prev.contains(":")) {
            String[] parts = prev.split(":");
            targetId = Long.parseLong(parts[0]);
            if (parts.length > 1) roleStr = parts[1];
        } else if (prev != null && !prev.isEmpty()) {
            targetId = Long.parseLong(prev);
        } else {
            targetId = chatId;
        }

        userRepo.updateName(targetId, newName);

        if (targetId == chatId) {
            user.setName(newName);
        }

        user.setState(UserState.EMPTY_STATE);
        user.setPreviousMessage("");
        userRepo.save(user);

        Keyboard backKb = "ADMIN".equalsIgnoreCase(roleStr) 
                ? KeyboardBuilder.changeAdminListKeyboard() 
                : KeyboardBuilder.changeUserListKeyboard();

        return TextResult.build(chatId, "Имя успешно изменено на: " + newName, backKb);
    }

    private String getRoleName(Role role) {
        return switch (role) {
            case USER -> "пользователей";
            case ADMIN -> "администраторов";
            case APPROVER -> "утверждающих";
            case RECEIVER -> "получателей";
            case REGISTRAR -> "регистраторов";
        };
    }

    private String getSingularRoleName(Role role) {
        return switch (role) {
            case USER -> "пользователь";
            case ADMIN -> "администратор";
            case APPROVER -> "утверждающий";
            case RECEIVER -> "получатель";
            case REGISTRAR -> "регистратор";
        };
    }

    private String getSingularRoleNameGenitive(Role role) {
        return switch (role) {
            case USER -> "пользователя";
            case ADMIN -> "администратора";
            case APPROVER -> "утверждающего";
            case RECEIVER -> "получателя";
            case REGISTRAR -> "регистратора";
        };
    }

    // === Блокировка документа ===

    private DocResult handleBlockReason(long chatId, User user, String reason, UserState state) {
        String prevMsg = user.getPreviousMessage();
        if (prevMsg == null || !prevMsg.matches("\\d+:\\w+")) {
            user.setState(UserState.EMPTY_STATE);
            user.setPreviousMessage("");
            userRepo.save(user);
            return TextResult.build(chatId, "Ошибка: документ не найден.");
        }
        String[] parts = prevMsg.split(":");
        int docId = Integer.parseInt(parts[0]);
        Stage savedStage = Stage.valueOf(parts[1]);
        user.setState(UserState.EMPTY_STATE);
        user.setPreviousMessage("");
        userRepo.save(user);

        Optional<Paper> paperOpt = paperRepo.findById(docId);
        if (paperOpt.isEmpty()) {
            return TextResult.build(chatId, "Документ #" + docId + " не найден");
        } else {
        Paper paper = paperOpt.get();
        paper.setRepo(paperRepo);
        String actorName = userRepo.getName(chatId);

        if (paper.getStage() != savedStage) {
            return TextResult.build(chatId, "Документ #" + docId + " больше не на этой стадии. Текущая стадия: " + paper.getStage());
        }

        // Сверка ID только если кто-то уже взял в работу
        Stage currentStage = paper.getStage();
        if (currentStage == Stage.ACCEPTING || currentStage == Stage.REGISTERING || currentStage == Stage.PAYING) {
            boolean isAssigned = switch (state) {
                case ENTERING_APPROVING_BLOCK_REASON -> paper.getAcceptorId() == chatId;
                case ENTERING_REGISTRATION_BLOCK_REASON -> paper.getRegistrarId() == chatId;
                case ENTERING_PAYMENT_BLOCK_REASON -> paper.getReceiverId() == chatId;
                default -> false;
            };
            if (!isAssigned) {
                return TextResult.build(chatId, "Документ #" + docId + " больше не назначен вам.");
            }
        }

        DocResult blockResult = switch (state) {
            case ENTERING_APPROVING_BLOCK_REASON -> paper.blockApproving(chatId, actorName, reason);
            case ENTERING_REGISTRATION_BLOCK_REASON -> paper.blockRegistration(chatId, actorName, reason);
            case ENTERING_PAYMENT_BLOCK_REASON -> paper.blockPaying(chatId, actorName, reason);
            default -> TextResult.build(chatId, "Ошибка");
        };

        String confirmText = switch (state) {
            case ENTERING_APPROVING_BLOCK_REASON -> "Принято. Документ заблокирован.";
            case ENTERING_REGISTRATION_BLOCK_REASON -> "Принято. Отказ в регистрации зафиксирован.";
            case ENTERING_PAYMENT_BLOCK_REASON -> "Принято. Отказ в оплате зафиксирован.";
            default -> "Принято.";
        };
        blockResult.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, confirmText, null, null));
        return blockResult;
        }
    }

    // === Утилиты ===
    private DocResult splitAndSendResult(long chatId, String header, String body, Keyboard markup) {
        DocResult result = new DocResult();
        int maxChunkSize = 3800;
        String fullText = (header != null && !header.isEmpty()) ? header + "\n" + body : body;

        if (fullText.length() <= maxChunkSize) {
            result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, fullText, null, markup));
            return result;
        }

        String[] lines = fullText.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        List<String> chunks = new ArrayList<>();

        for (String line : lines) {
            if (currentChunk.length() + line.length() + 1 > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                while (line.length() > maxChunkSize) {
                    chunks.add(line.substring(0, maxChunkSize));
                    line = line.substring(maxChunkSize);
                }
            }
            if (currentChunk.length() > 0) {
                currentChunk.append("\n");
            }
            currentChunk.append(line);
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        for (int i = 0; i < chunks.size(); i++) {
            boolean isLast = (i == chunks.size() - 1);
            result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, chunks.get(i), null, isLast ? markup : null));
        }

        return result;
    }
}
