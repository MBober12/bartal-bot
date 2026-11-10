package bot.model;

import bot.keyboard.KeyboardBuilder;
import bot.scheduler.Reminder;
import bot.repository.PaperRepository;
import bot.repository.ReminderRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Paper {
    public enum FileType { PHOTO, DOCUMENT }

    private final int docId;
    private final DocType docType;
    private final FileType fileType;
    private final String details;
    private final String fileId;

    private Stage stage;
    private String statusNote;
    private String reason;
    private Project project;
    private boolean needsRegistration = false;

    private long userId;
    private long acceptorId;
    private long registrarId;
    private long receiverId;

    private HashMap<Long, Integer> acceptors;
    private HashMap<Long, Integer> registrars;
    private HashMap<Long, Integer> receivers;

    private long userTime;
    private long acceptorTime;
    private long registrarTime;
    private long receiverTime;

    private PaperRepository repo;
    private ReminderRepository reminderRepo;

    public Paper(int docId, DocType docType, FileType fileType, String details, Project project, String fileId) {
        this.docId = docId;
        this.docType = docType;
        this.fileType = fileType;
        this.details = details;
        this.fileId = fileId;
        this.project = project;
    }

    public void setRepo(PaperRepository repo) {
        this.repo = repo;
    }

    public void setReminderRepo(ReminderRepository reminderRepo) {
        this.reminderRepo = reminderRepo;
    }

    private DocResult newResult() {
        DocResult result = new DocResult();
        result.setDocId(docId);
        return result;
    }

    private DocResult accessDenied(long chatId) {
        DocResult result = new DocResult();
        result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, "У вас нет доступа для выполнения этого действия", null, KeyboardBuilder.removeKeyboard()));
        return result;
    }

    // === Бизнес-методы ===

    public DocResult submit(long chatId, String name) {
        if (project.getUsers().contains(chatId)) {
            this.stage = Stage.SUBMITTED;
            this.userId = chatId;
            this.userTime = System.currentTimeMillis();
            appendStatusNote("Пользователь " + name + " передал документ, " + formatTime(userTime));
            repo.save(this);

            String caption = getFormattedCaption();

            Keyboard markup = KeyboardBuilder.build(this);

            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Ваш документ успешно передан!\nDocId - " + docId, fileId, KeyboardBuilder.removeKeyboard()));

            // Получателям — новое сообщение
            Role roleToSend = null;
            if (docType.equals(DocType.INVOICE))
                roleToSend = Role.APPROVER;
            else if (docType.equals((DocType.ACT)))
                roleToSend = Role.RECEIVER;
            result.addTask(new BotTask(sendType, roleToSend, project, caption, fileId, markup));

            return result;
        } else {
            return accessDenied(chatId);
        }

    }


    public DocResult startApproving(long chatId, String name) {
        if (project.getApprovers().contains(chatId)) {
            this.stage = Stage.ACCEPTING;
            this.acceptorId = chatId;
            this.acceptorTime = System.currentTimeMillis();
            createReminderForUser(chatId, getApprovers() != null && getApprovers().containsKey(chatId) ? getMessageIdForRole(getApprovers(), chatId) : 0, getReminderStageForSubmit());
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();
            String caption = formatTime(acceptorTime) + "\n" + getFormattedCaption();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Утверждающему — caption + клавиатура
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getApprovers(), chatId), "Вы начали согласование документа, " + caption, null, markup));

            // Остальным утверждающим — caption без кнопок
            HashMap<Long, Integer> otherAcceptors = new HashMap<>(getApprovers());
            otherAcceptors.remove(chatId);
            if (!otherAcceptors.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherAcceptors, "Утверждающий " + name + " начал согласование, " + caption, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Утверждающий " + name + " начал согласование вашего документа, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult returnToApprovers(long chatId, String name) {
        //тут не проверяем потому что даже если его выгнали, пусть будет возможность хотя б документ вернуть обратно
        this.stage = Stage.SUBMITTED;
        this.acceptorId = 0;
        this.acceptorTime = 0;
        deleteAllReminders();
        repo.save(this);

        Keyboard markup = KeyboardBuilder.build(this);


        DocResult result = newResult();
        String caption = getFormattedCaption();

        BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

        // Всем утверждающим — caption с кнопками
        HashMap<Long, Integer> acceptors = getApprovers();
        if (acceptors != null && !acceptors.isEmpty()) {
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, acceptors, caption, null, markup));

            // Утверждающему — ответ об успешном возврате
            HashMap<Long, Integer> acceptor = new HashMap<>();
            acceptor.put(chatId, getMessageIdForRole(acceptors, chatId));
            result.addTask(new BotTask(BotTask.Type.ANSWER_CALLBACK, chatId, "Вы успешно вернули документ утверждающим, они будут уведомлены об этом", null, KeyboardBuilder.removeKeyboard()));
            for (Map.Entry<Long, Integer> entry : acceptors.entrySet()) {
                if (entry.getKey() == chatId) continue;
                BotTask notify = new BotTask(BotTask.Type.SEND_TEXT, entry.getKey(), "Утверждающий " + name + " вернул документ на согласование, он снова доступен для взаимодействия", null, KeyboardBuilder.removeKeyboard());
                notify.setReplyToMessageId(entry.getValue());
                result.addTask(notify);
            }
        }

        // Создателю — уведомление (пропускаем если создатель = утверждающий)
        if (userId != chatId) {
            result.addTask(new BotTask(sendType, userId, "Утверждающий " + name + " вернул ваш документ на согласование, " + formatTime(System.currentTimeMillis()) + caption, fileId, KeyboardBuilder.removeKeyboard()));
        }

        return result;
    }

    public DocResult approveNoRegistration(long chatId, String name) {
        if (project.getApprovers().contains(chatId)) {
            this.stage = Stage.ACCEPTED_NO_REG;
            this.acceptorId = chatId;
            this.acceptorTime = System.currentTimeMillis();
            this.needsRegistration = false;
            deleteAllReminders();
            String caption = formatTime(acceptorTime) + "\n" + getFormattedCaption();
            appendStatusNote("Утверждающий " + name + " согласовал без регистрации, " + formatTime(acceptorTime));
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Утверждающему — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getApprovers(), chatId), "Вы согласовали без регистрации, " + caption, null, KeyboardBuilder.removeKeyboard()));

            // Остальным утверждающим — caption без кнопок
            HashMap<Long, Integer> otherAcceptors = new HashMap<>(getApprovers());
            otherAcceptors.remove(chatId);
            if (!otherAcceptors.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherAcceptors, getFormattedCaption(), null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Утверждающий " + name + " согласовал без регистрации ваш документ, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            // Получателям — новое сообщение
            result.addTask(new BotTask(sendType, Role.RECEIVER, project, getFormattedCaption(), fileId, markup));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult approveWithRegistration(long chatId, String name) {
        if (project.getApprovers().contains(chatId)) {
            this.stage = Stage.ACCEPTED_WITH_REG;
            this.acceptorId = chatId;
            this.acceptorTime = System.currentTimeMillis();
            this.needsRegistration = true;
            deleteAllReminders();
            String caption = formatTime(acceptorTime) + "\n" + getFormattedCaption();
            appendStatusNote("Утверждающий " + name + " согласовал с регистрацией, " + formatTime(acceptorTime));
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Утверждающему — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getApprovers(), chatId), "Вы согласовали с регистрацией, " + caption, null, KeyboardBuilder.removeKeyboard()));

            // Остальным утверждающим — caption без кнопок
            HashMap<Long, Integer> otherAcceptors = new HashMap<>(getApprovers());
            otherAcceptors.remove(chatId);
            if (!otherAcceptors.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherAcceptors, getFormattedCaption(), null, KeyboardBuilder.removeKeyboard()));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Утверждающий " + name + " согласовал с регистрацией ваш документ, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            // Регистраторам — новое сообщение
            result.addTask(new BotTask(sendType, Role.REGISTRAR, project, getFormattedCaption(), fileId, markup));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult blockApproving(long chatId, String name, String reason) {
        if (project.getApprovers().contains(chatId)) {
            this.stage = Stage.BLOCKED_AT_ACCEPTANCE;
            this.acceptorId = chatId;
            this.acceptorTime = System.currentTimeMillis();
            this.reason = reason;
            deleteAllReminders();
            String reasonLine = reason != null && !reason.isEmpty() ? "\nПричина: " + reason : "";
            String caption = formatTime(acceptorTime) + "\n" + getFormattedCaption();
            appendStatusNote("Утверждающий " + name + " заблокировал, " + formatTime(acceptorTime));
            repo.save(this);

            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Утверждающему — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getApprovers(), chatId), "Вы заблокировали, " + caption + reasonLine, null, new Keyboard(true)));

            // Остальным утверждающим — caption без кнопок
            HashMap<Long, Integer> otherAcceptors = new HashMap<>(getApprovers());
            otherAcceptors.remove(chatId);
            if (!otherAcceptors.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherAcceptors, getFormattedCaption() + reasonLine, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            String reasonHtml = reason != null && !reason.isEmpty() ? "\n<u><b>Причина: " + reason + "</b></u>" : "";
            result.addTask(new BotTask(sendType, userId, "<b><u>Утверждающий " + name + " заблокировал ваш документ</u></b>, " + caption + reasonHtml, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult startRegistering(long chatId, String name) {
        if (project.getRegistrars().contains(chatId)) {
            this.stage = Stage.REGISTERING;
            this.registrarId = chatId;
            this.registrarTime = System.currentTimeMillis();
            createReminderForUser(chatId, getRegistrars() != null && getRegistrars().containsKey(chatId) ? getMessageIdForRole(getRegistrars(), chatId) : 0, getReminderStageForRegister());
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();
            String caption = formatTime(registrarTime) + "\n" + getFormattedCaption();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Регистратору — caption + клавиатура
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getRegistrars(), chatId), "Вы начали регистрацию документа, " + caption, null, markup));

            // Остальным регистраторам — caption без кнопок
            HashMap<Long, Integer> otherRegistrars = new HashMap<>(getRegistrars());
            otherRegistrars.remove(chatId);
            if (!otherRegistrars.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherRegistrars, "Регистратор " + name + " начал регистрацию, " + caption, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Регистатор " + name + " начал регистрацию вашего документа, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult returnToRegistrars(long chatId, String name) {
        //та же логика, если его выгнали, ничего плохого от возврата не случится
        this.stage = Stage.ACCEPTED_WITH_REG;
        this.registrarId = 0;
        this.registrarTime = 0;
        deleteAllReminders();
        repo.save(this);

        Keyboard markup = KeyboardBuilder.build(this);
        String caption = getFormattedCaption();

        DocResult result = newResult();

        BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

        // Всем регистраторам — caption с кнопками
        HashMap<Long, Integer> registrars = getRegistrars();
        if (registrars != null && !registrars.isEmpty()) {
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, registrars, caption, null, markup));

            // Регистратору — ответ об успешном возврате
            HashMap<Long, Integer> registrar = new HashMap<>();
            registrar.put(chatId, getMessageIdForRole(registrars, chatId));
            result.addTask(new BotTask(BotTask.Type.ANSWER_CALLBACK, chatId, "Вы успешно вернули документ регистраторам, они будут уведомлены об этом", null, KeyboardBuilder.removeKeyboard()));
            for (Map.Entry<Long, Integer> entry : registrars.entrySet()) {
                if (entry.getKey() == chatId) continue;
                BotTask notify = new BotTask(BotTask.Type.SEND_TEXT, entry.getKey(), "Регистратор " + name + " вернул документ на регистрацию, он снова доступен для взаимодействия", null, KeyboardBuilder.removeKeyboard());
                notify.setReplyToMessageId(entry.getValue());
                result.addTask(notify);
            }
        }

        // Создателю — уведомление (пропускаем если создатель = регистратор)
        if (userId != chatId) {
            result.addTask(new BotTask(sendType, userId, "Регистратор " + name + " вернул ваш документ на регистрацию, " + formatTime(System.currentTimeMillis()) + caption, fileId, KeyboardBuilder.removeKeyboard()));
        }

        return result;
    }

    public DocResult register(long chatId, String name) {
        if (project.getRegistrars().contains(chatId)) {
            this.stage = Stage.REGISTERED;
            this.registrarId = chatId;
            this.registrarTime = System.currentTimeMillis();
            deleteAllReminders();
            String caption = formatTime(registrarTime) + "\n" + getFormattedCaption();
            appendStatusNote("Регистратор " + name + " зарегистрировал, " + formatTime(registrarTime));
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Регистратору — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getRegistrars(), chatId), "Вы зарегистрировали, " + caption, null, KeyboardBuilder.removeKeyboard()));

            // Остальным регистраторам — caption без кнопок
            HashMap<Long, Integer> otherRegistrars = new HashMap<>(getRegistrars());
            otherRegistrars.remove(chatId);
            if (!otherRegistrars.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherRegistrars, "Регистратор " + name + " зарегистрировал, " + caption, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Регистратор " + name + " зарегистрировал ваш документ, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            // Получателям — новое сообщение
            result.addTask(new BotTask(sendType, Role.RECEIVER, project, getFormattedCaption(), fileId, markup));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult blockRegistration(long chatId, String name, String reason) {
        if (project.getRegistrars().contains(chatId)) {
            this.stage = Stage.BLOCKED_AT_REGISTRATION;
            this.registrarId = chatId;
            this.registrarTime = System.currentTimeMillis();
            this.reason = reason;
            deleteAllReminders();
            String reasonLine = reason != null && !reason.isEmpty() ? "\nПричина: " + reason : "";
            String caption = formatTime(registrarTime) + "\n" + getFormattedCaption();
            appendStatusNote("Регистратор " + name + " отказал в регистрации, " + formatTime(registrarTime));
            repo.save(this);

            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            // Регистратору — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getRegistrars(), chatId), "Вы отказали в регистрации, " + caption + reasonLine, null, new Keyboard(true)));

            // Остальным регистраторам — caption без кнопок
            HashMap<Long, Integer> otherRegistrars = new HashMap<>(getRegistrars());
            otherRegistrars.remove(chatId);
            if (!otherRegistrars.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherRegistrars, getFormattedCaption() + reasonLine, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            String reasonHtml = reason != null && !reason.isEmpty() ? "\n<u><b>Причина: " + reason + "</b></u>" : "";
            result.addTask(new BotTask(sendType, userId, "<b><u>Регистратор " + name + " отказал в регистрации вашего документа</u></b>, " + caption + reasonHtml, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult startPaying(long chatId, String name) {
        if (project.getReceivers().contains(chatId)) {
            this.stage = Stage.PAYING;
            this.receiverId = chatId;
            this.receiverTime = System.currentTimeMillis();
            createReminderForUser(chatId, getReceivers() != null && getReceivers().containsKey(chatId) ? getMessageIdForRole(getReceivers(), chatId) : 0, getReminderStageForAccept());
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();
            String caption = formatTime(receiverTime) + "\n" + getFormattedCaption();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            String word = null;
            if (docType.equals(DocType.INVOICE))
                word = "оплату";
            else if (docType.equals((DocType.ACT)))
                word = "приемку";

            // Получателю — caption + клавиатура
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getReceivers(), chatId), "Вы начали " + word + " документа, " + caption, null, markup));

            // Остальным получателям — caption без кнопок
            HashMap<Long, Integer> otherReceivers = new HashMap<>(getReceivers());
            otherReceivers.remove(chatId);
            if (!otherReceivers.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherReceivers, "Получатель " + name + " начал " + word + ", " + caption, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "Получатель " + name + " начал " + word + " вашего документа, " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult returnToReceivers(long chatId, String name) {
        //все то же самое
        if (docType.equals(DocType.INVOICE)) {
            if (needsRegistration)
                this.stage = Stage.REGISTERED;
            else
                this.stage = Stage.ACCEPTED_NO_REG;
        }
        else if (docType.equals((DocType.ACT)))
            this.stage = Stage.SUBMITTED;

        this.receiverId = 0;
        this.receiverTime = 0;
        deleteAllReminders();
        repo.save(this);

        Keyboard markup = KeyboardBuilder.build(this);


        DocResult result = newResult();
        String caption = getFormattedCaption();

        BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

        String word = null;
        if (docType.equals(DocType.INVOICE))
            word = "оплату";
        else if (docType.equals((DocType.ACT)))
            word = "приемку";

        // Всем получателям — caption с кнопками
        HashMap<Long, Integer> receivers = getReceivers();
        if (receivers != null && !receivers.isEmpty()) {
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, receivers, caption, null, markup));

            // Получателю — ответ об успешном возврате
            HashMap<Long, Integer> receiver = new HashMap<>();
            receiver.put(chatId, getMessageIdForRole(receivers, chatId));
            result.addTask(new BotTask(BotTask.Type.ANSWER_CALLBACK, chatId, "Вы успешно вернули документ получателям, они будут уведомлены об этом", null, KeyboardBuilder.removeKeyboard()));
            for (Map.Entry<Long, Integer> entry : receivers.entrySet()) {
                if (entry.getKey() == chatId) continue;
                BotTask notify = new BotTask(BotTask.Type.SEND_TEXT, entry.getKey(), "Получатель " + name + " вернул документ на " + word + ", он снова доступен для взаимодействия", null, KeyboardBuilder.removeKeyboard());
                notify.setReplyToMessageId(entry.getValue());
                result.addTask(notify);
            }
        }

        // Создателю — уведомление (пропускаем если создатель = получатель)
        if (userId != chatId) {
            result.addTask(new BotTask(sendType, userId, "Получатель " + name + " вернул ваш документ на " + word + ", " + formatTime(System.currentTimeMillis()) + caption, fileId, KeyboardBuilder.removeKeyboard()));
        }

        return result;
    }

    public DocResult pay(long chatId, String name) {
        if (project.getReceivers().contains(chatId)) {
            this.stage = Stage.PAID;
            this.receiverId = chatId;
            this.receiverTime = System.currentTimeMillis();
            deleteAllReminders();
            String caption = formatTime(receiverTime) + "\n" + getFormattedCaption();
            if (docType.equals(DocType.INVOICE))
                appendStatusNote("Получатель " + name + " оплатил, " + formatTime(receiverTime));
            else if (docType.equals((DocType.ACT)))
                appendStatusNote("Получатель " + name + " принял, " + formatTime(receiverTime));
            repo.save(this);

            Keyboard markup = KeyboardBuilder.build(this);


            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;

            String word = null;
            if (docType.equals(DocType.INVOICE))
                word = "оплатил";
            else if (docType.equals((DocType.ACT)))
                word = "принял";

            // Получателю — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getReceivers(), chatId), "Вы " + word + "и" + ", " + caption, null, KeyboardBuilder.removeKeyboard()));

            // Остальным получателям — caption без кнопок
            HashMap<Long, Integer> otherReceivers = new HashMap<>(getReceivers());
            otherReceivers.remove(chatId);
            if (!otherReceivers.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherReceivers, "Получатель " + name + " " + word + ", " + caption, null, new Keyboard(true)));
            }

            // Создателю — уведомление
            result.addTask(new BotTask(sendType, userId, "<b><u>Получатель " + name + " " + word + " ваш документ,</u></b> " + caption, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    public DocResult blockPaying(long chatId, String name, String reason) {
        if (project.getReceivers().contains(chatId)) {
            this.stage = Stage.BLOCKED_AT_PAYMENT;
            this.receiverId = chatId;
            this.receiverTime = System.currentTimeMillis();
            this.reason = reason;
            deleteAllReminders();

            String word = docType == DocType.INVOICE ? "оплате" : "приемке";
            String reasonLine = reason != null && !reason.isEmpty() ? "\nПричина: " + reason : "";
            String caption = formatTime(receiverTime) + "\n" + getFormattedCaption();
            appendStatusNote("Получатель " + name + " отказал в " + word + ", " + formatTime(receiverTime));
            repo.save(this);


            DocResult result = newResult();

            BotTask.Type sendType = fileType == FileType.DOCUMENT ? BotTask.Type.SEND_DOCUMENT : BotTask.Type.SEND_PHOTO;


            // Получателю — caption без кнопок
            result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, chatId, getMessageIdForRole(getReceivers(), chatId), "Вы отказали в " + word + ", " + caption + reasonLine, null, new Keyboard(true)));

            // Остальным получателям — caption без кнопок
            HashMap<Long, Integer> otherReceivers = new HashMap<>(getReceivers());
            otherReceivers.remove(chatId);
            if (!otherReceivers.isEmpty()) {
                result.addTask(new BotTask(BotTask.Type.EDIT_CAPTION, otherReceivers, "Получатель " + name + " отказал в " + word + ", " + caption + reasonLine, null, new Keyboard(true)));
            }

            // Создателю — уведомлениеe
            String reasonHtml = reason != null && !reason.isEmpty() ? "\n<u><b>Причина: " + reason + "</b></u>" : "";
            result.addTask(new BotTask(sendType, userId, "<b><u>Получатель " + name + " отказал в " + word + " вашего документа</u></b>, " + caption + reasonHtml, fileId, KeyboardBuilder.removeKeyboard()));

            return result;
        } else {
            return accessDenied(chatId);
        }
    }

    // Getters (final — только чтение)
    public int getDocId() { return docId; }
    public DocType getDocType() { return docType; }
    public FileType getFileType() { return fileType; }
    public String getDetails() { return details; }
    public String getFileId() { return fileId; }

    // Getters
    public Project getProject() { return project; }
    public Stage getStage() { return stage; }
    public String getStatusNote() { return statusNote; }
    public String getReason() { return reason; }
    public boolean getNeedsRegistration() { return needsRegistration; }
    public long getUserId() { return userId; }
    public long getAcceptorId() { return acceptorId; }
    public long getRegistrarId() { return registrarId; }
    public long getReceiverId() { return receiverId; }
        private int getMessageIdForRole(HashMap<Long, Integer> map, long chatId) {
        if (map == null || !map.containsKey(chatId)) return 0;
        Integer val = map.get(chatId);
        return val != null ? val : 0;
    }

    public HashMap<Long, Integer> getApprovers() { return acceptors; }
    public HashMap<Long, Integer> getRegistrars() { return registrars; }
    public HashMap<Long, Integer> getReceivers() { return receivers; }
    public long getUserTime() { return userTime; }
    public long getAcceptorTime() { return acceptorTime; }
    public long getRegistrarTime() { return registrarTime; }
    public long getReceiverTime() { return receiverTime; }

    // Setters (без final полей)
    public void setStage(Stage stage) { this.stage = stage; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }

    public void appendStatusNote(String line) {
        if (statusNote == null || statusNote.isEmpty()) {
            statusNote = line;
        } else {
            statusNote = line + "\n" + statusNote;
        }
    }
    public void setReason(String reason) { this.reason = reason; }
    public void setNeedsRegistration(boolean needsRegistration) { this.needsRegistration = needsRegistration; }
    public void setUserId(long userId) { this.userId = userId; }
    public void setAcceptorId(long acceptorId) { this.acceptorId = acceptorId; }
    public void setRegistrarId(long registrarId) { this.registrarId = registrarId; }
    public void setReceiverId(long receiverId) { this.receiverId = receiverId; }
    public void setApprovers(HashMap<Long, Integer> acceptors) { this.acceptors = acceptors; }
    public void setRegistrars(HashMap<Long, Integer> registrars) { this.registrars = registrars; }
    public void setReceivers(HashMap<Long, Integer> receivers) { this.receivers = receivers; }
    public void setUserTime(long userTime) { this.userTime = userTime; }
    public void setAcceptorTime(long acceptorTime) { this.acceptorTime = acceptorTime; }
    public void setRegistrarTime(long registrarTime) { this.registrarTime = registrarTime; }
    public void setReceiverTime(long receiverTime) { this.receiverTime = receiverTime; }

    public String getFormattedCaption() {
        StringBuilder sb = new StringBuilder();
        if (statusNote != null && !statusNote.isEmpty()) {
            sb.append(statusNote);
            sb.append("\n");
        }
        if (details != null && !details.isEmpty()) {
            sb.append("<blockquote>");
            for (String line : details.split("\n")) {
                sb.append(highlightDetails(line));
                sb.append("\n");
            }
            sb.append("</blockquote>");
        }
        sb.append("\n\nDocId — ").append(docId);
        return sb.toString();
    }

    private static String highlightDetails(String line) {
        if (line.startsWith("2.Объект/проект - ")) {
            return line.replaceAll("\\(([^)]+)(\\))?", "<b><i><u>($1$2</u></i></b>");
        }
        return line;
    }

    public Set<Stage> getAllowedTargets() {
        return switch (getStage()) {
            case SUBMITTED -> {
                if (getDocType() == DocType.INVOICE)
                    yield Set.of(Stage.ACCEPTING, Stage.BLOCKED_AT_ACCEPTANCE);
                else // ACT
                    yield Set.of(Stage.PAYING, Stage.BLOCKED_AT_PAYMENT);
            }
            case ACCEPTING -> Set.of(
                    Stage.ACCEPTED_NO_REG, Stage.ACCEPTED_WITH_REG,
                    Stage.BLOCKED_AT_ACCEPTANCE, Stage.SUBMITTED
            );
            case ACCEPTED_NO_REG -> Set.of(Stage.PAYING, Stage.BLOCKED_AT_PAYMENT);
            case ACCEPTED_WITH_REG -> Set.of(Stage.REGISTERING, Stage.BLOCKED_AT_REGISTRATION);
            case REGISTERING -> Set.of(
                    Stage.REGISTERED, Stage.BLOCKED_AT_REGISTRATION, Stage.ACCEPTED_WITH_REG
            );
            case REGISTERED -> Set.of(Stage.PAYING, Stage.BLOCKED_AT_PAYMENT);
            case PAYING -> Set.of(Stage.PAID, Stage.BLOCKED_AT_PAYMENT, Stage.REGISTERED, Stage.ACCEPTED_NO_REG, Stage.SUBMITTED);
            default -> Set.of();
        };
    }

    public static String formatTime(long millis) {
        if (millis == 0) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        return sdf.format(new Date(millis));
    }

    public void createReminderForRole(HashMap<Long, Integer> roleMap, String reminderStage) {
        if (reminderRepo == null || roleMap == null || roleMap.isEmpty()) return;
        int initialReminds = Reminder.getDelay() < 15 ? 0 : 1;
        for (Map.Entry<Long, Integer> entry : roleMap.entrySet()) {
            reminderRepo.insert(docId, String.valueOf(entry.getKey()), entry.getValue(), reminderStage, initialReminds);
        }
    }

    public void createReminderForUser(long chatId, int messageId, String reminderStage) {
        if (reminderRepo == null) return;
        reminderRepo.deleteByDocId(docId);
        int initialReminds = Reminder.getDelay() < 15 ? 0 : 1;
        reminderRepo.insert(docId, String.valueOf(chatId), messageId, reminderStage, initialReminds);
    }

    public void deleteAllReminders() {
        if (reminderRepo == null) return;
        reminderRepo.deleteByDocId(docId);
    }

    public String getReminderStageForSubmit() {
        if (docType.equals(DocType.INVOICE))
            return "согласование";
        else
            return "приемку";
    }

    public String getReminderStageForAccept() {
        if (docType.equals(DocType.INVOICE))
            return "оплату";
        else
            return "приемку";
    }

    public String getReminderStageForRegister() {
        return "оплату";
    }

    public String getStatusSummary(bot.repository.UserRepository userRepo) {
        StringBuilder sb = new StringBuilder("DocId ").append(docId).append(". ");

        if (docType == DocType.INVOICE) {
            if (receiverId != 0) {
                if (stage == Stage.PAID)
                    sb.append("Оплачен получателем ").append(userRepo.getName(receiverId));
                else if (stage == Stage.BLOCKED_AT_PAYMENT)
                    sb.append("Отказал в оплате получатель ").append(userRepo.getName(receiverId));
                else
                    sb.append("Начал оплату получатель ").append(userRepo.getName(receiverId)).append(". Ожидает окончания оплаты...");
            } else if (needsRegistration) {
                if (registrarId != 0) {
                    if (stage == Stage.BLOCKED_AT_REGISTRATION)
                        sb.append("Отказал в регистрации регистратор ").append(userRepo.getName(registrarId));
                    else if (stage == Stage.REGISTERED || receivers != null && !receivers.isEmpty())
                        sb.append("Зарегистрирован регистратором ").append(userRepo.getName(registrarId)).append(". Ожидает оплаты...");
                    else
                        sb.append("Начал регистрацию регистратор ").append(userRepo.getName(registrarId)).append(". Ожидает окончания регистрации...");
                } else if (acceptorId != 0)
                    sb.append("Согласован с регистрацией утверждающим ").append(userRepo.getName(acceptorId)).append(". Ожидает регистрации...");
                else
                    sb.append("Ожидает согласования...");
            } else {
                if (acceptorId != 0) {
                    if (stage == Stage.BLOCKED_AT_ACCEPTANCE)
                        sb.append("Заблокирован утверждающим ").append(userRepo.getName(acceptorId));
                    else if (stage == Stage.ACCEPTED_NO_REG || receivers != null && !receivers.isEmpty())
                        sb.append("Согласован без регистрации утверждающим ").append(userRepo.getName(acceptorId)).append(". Ожидает оплаты...");
                    else
                        sb.append("Начал согласование утверждающий ").append(userRepo.getName(acceptorId)).append(". Ожидает окончания согласования...");
                } else
                    sb.append("Ожидает согласования...");
            }
        } else { // ACT
            if (receiverId != 0) {
                if (stage == Stage.PAID)
                    sb.append("Принял ").append(userRepo.getName(receiverId));
                else if (stage == Stage.BLOCKED_AT_PAYMENT)
                    sb.append("Отказал в приемке ").append(userRepo.getName(receiverId));
                else
                    sb.append("Начал приемку получатель ").append(userRepo.getName(receiverId)).append(". Ожидает окончания приемки...");
            } else
                sb.append("Ожидает приемки...");
        }
        return sb.toString();
    }

    public String getFullInfo(bot.repository.UserRepository userRepo) {
        StringBuilder sb = new StringBuilder();
        if (details != null && !details.isEmpty()) {
            sb.append("<blockquote>");
            for (String line : details.split("\n")) {
                sb.append(highlightDetails(line));
                sb.append("\n");
            }
            sb.append("</blockquote>").append("\n");
        }

        sb.append("Передал: ").append(userRepo.getName(userId)).append(" ").append(formatTime(userTime)).append("\n");

        if (docType == DocType.INVOICE) {
            if (acceptorId != 0) {
                if (stage == Stage.BLOCKED_AT_ACCEPTANCE) {
                    sb.append("Заблокировал: ").append(userRepo.getName(acceptorId));
                    if (reason != null && !reason.isEmpty()) sb.append(" по причине \"").append(reason).append("\"");
                    sb.append(" ").append(formatTime(acceptorTime)).append("\n");
                } else if (needsRegistration) {
                    sb.append("Согласовал с регистрацией: ").append(userRepo.getName(acceptorId)).append(" ").append(formatTime(acceptorTime)).append("\n");
                    if (registrarId != 0) {
                        if (stage == Stage.BLOCKED_AT_REGISTRATION) {
                            sb.append("Отказал в регистрации: ").append(userRepo.getName(registrarId));
                            if (reason != null && !reason.isEmpty()) sb.append(" по причине \"").append(reason).append("\"");
                            sb.append(" ").append(formatTime(registrarTime)).append("\n");
                        } else if (receivers != null && !receivers.isEmpty()) {
                            sb.append("Зарегистрировал: ").append(userRepo.getName(registrarId)).append(" ").append(formatTime(registrarTime)).append("\n");
                        } else {
                            sb.append("Начал регистрацию: ").append(userRepo.getName(registrarId)).append(" ").append(formatTime(registrarTime)).append("\n");
                            sb.append("Ожидает окончания регистрации...\n");
                        }
                    } else {
                        sb.append("Ожидает регистрации...\n");
                    }
                } else {
                    sb.append("Согласовал без регистрации: ").append(userRepo.getName(acceptorId)).append(" ").append(formatTime(acceptorTime)).append("\n");
                }

                if (receiverId != 0) {
                    if (stage == Stage.PAID) {
                        sb.append("Оплатил: ").append(userRepo.getName(receiverId)).append(" ").append(formatTime(receiverTime)).append("\n");
                    } else if (stage == Stage.BLOCKED_AT_PAYMENT) {
                        sb.append("Отказал в оплате: ").append(userRepo.getName(receiverId));
                        if (reason != null && !reason.isEmpty()) sb.append(" по причине \"").append(reason).append("\"");
                        sb.append(" ").append(formatTime(receiverTime)).append("\n");
                    } else {
                        sb.append("Начал оплату: ").append(userRepo.getName(receiverId)).append(" ").append(formatTime(receiverTime)).append("\n");
                        sb.append("Ожидает окончания оплаты...\n");
                    }
                } else if (stage == Stage.ACCEPTED_NO_REG || stage == Stage.REGISTERED) {
                    sb.append("Ожидает оплаты...\n");
                }
            } else {
                sb.append("Ожидает согласования...\n");
            }
        } else { // ACT
            if (receiverId != 0) {
                if (stage == Stage.PAID) {
                    sb.append("Принял: ").append(userRepo.getName(receiverId)).append(" ").append(formatTime(receiverTime)).append("\n");
                } else if (stage == Stage.BLOCKED_AT_PAYMENT) {
                    sb.append("Отказал в приемке: ").append(userRepo.getName(receiverId));
                    if (reason != null && !reason.isEmpty()) sb.append(" по причине \"").append(reason).append("\"");
                    sb.append(" ").append(formatTime(receiverTime)).append("\n");
                } else {
                    sb.append("Начал приемку: ").append(userRepo.getName(receiverId)).append(" ").append(formatTime(receiverTime)).append("\n");
                    sb.append("Ожидает окончания приемки...\n");
                }
            } else {
                sb.append("Ожидает приемки...\n");
            }
        }
        return sb.toString();
    }
}
