package bot;

import bot.apiClient.TelegramApiClient;
import bot.apiClient.TelegramApiException;
import bot.handler.CallbackHandler;
import bot.handler.FileHandler;
import bot.handler.InlineHandler;
import bot.handler.TextHandler;
import bot.model.*;
import bot.repository.PaperRepository;
import bot.repository.ProjectRepository;
import bot.repository.ReminderRepository;
import bot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class Bot extends DefaultLongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    private final CallbackHandler callbackHandler;
    private final TextHandler textHandler;
    private final FileHandler fileHandler;
    private final InlineHandler inlineHandler;
    private final UserRepository userRepo;
    private final PaperRepository paperRepo;
    private final ProjectRepository projectRepo;
    private final ReminderRepository reminderRepo;
    private final TelegramApiClient api;

    public Bot(String botToken, CallbackHandler callbackHandler, TextHandler textHandler, FileHandler fileHandler, InlineHandler inlineHandler, UserRepository userRepo, PaperRepository paperRepo, ProjectRepository projectRepo, ReminderRepository reminderRepo, TelegramApiClient api) {
        this.callbackHandler = callbackHandler;
        this.textHandler = textHandler;
        this.fileHandler = fileHandler;
        this.inlineHandler = inlineHandler;
        this.userRepo = userRepo;
        this.paperRepo = paperRepo;
        this.projectRepo = projectRepo;
        this.reminderRepo = reminderRepo;
        this.api = api;
    }

    @Override
    public void consume(Update update) {
        try {
            log.debug("Получено обновление #{}", update.getUpdateId());
            if (update.hasCallbackQuery()) {
                long chatId = update.getCallbackQuery().getFrom().getId();
                String callbackData = update.getCallbackQuery().getData();
                String callbackQueryId = update.getCallbackQuery().getId();
                int mesId = update.getCallbackQuery().getMessage().getMessageId();
                DocResult result = callbackHandler.handleCallbackQuery(chatId, callbackData, mesId);
                if (result != null) {
                    result.setCallbackQueryId(callbackQueryId);
                    executeTasks(result);
                }
            } else if (update.hasMessage()) {
                long chatId = update.getMessage().getChatId();
                DocResult result = null;

                if (update.getMessage().hasText()) {
                    String text = update.getMessage().getText();
                    result = textHandler.handleText(chatId, text);
                } else if (update.getMessage().hasPhoto()) {
                    String fileId = update.getMessage().getPhoto().stream()
                            .sorted((a, b) -> b.getFileSize() - a.getFileSize())
                            .findFirst()
                            .map(p -> p.getFileId())
                            .orElse(null);
                    if (fileId != null) {
                        result = fileHandler.handlePhoto(chatId, fileId);
                    }
                } else if (update.getMessage().hasDocument()) {
                    String fileId = update.getMessage().getDocument().getFileId();
                    InputStream fileStream = null;
                    Optional<User> userOpt = userRepo.findById(chatId);
                    if (userOpt.isPresent()
                            && UserState.fromText(userOpt.get().getState()) == UserState.SENDING_PROJECTS_TABLE) {
                        fileStream = api.getDocumentFile(fileId);
                    }
                    try (InputStream stream = fileStream) {
                        result = fileHandler.handleDocument(chatId, fileId, stream);
                    }
                }
                if (result != null) {
                    executeTasks(result);
                }
            } else if (update.hasInlineQuery()) {
                long chatId = update.getInlineQuery().getFrom().getId();
                String inlineQueryId = update.getInlineQuery().getId();
                String inlineQueryData = update.getInlineQuery().getQuery();
                DocResult result = inlineHandler.handleInlineQuery(chatId, inlineQueryId, inlineQueryData);
                if (result != null) {
                    executeTasks(result);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка в consume() для обновления #{}", update.getUpdateId(), e);
        }
    }

    public void executeTasks(DocResult result) {
        // Для простых текстовых сообщений без docId
        if (result.getDocId() == 0) {
            for (BotTask task : result.getTasks()) {
                executeTask(task);
            }
            return;
        }

        Optional<Paper> paperOpt = paperRepo.findById(result.getDocId());
        if (paperOpt.isEmpty()) {
            log.warn("Документ #{} не найден при исполнении задач", result.getDocId());
            return;
        }
        Paper paper = paperOpt.get();
        paper.setRepo(paperRepo);
        paper.setReminderRepo(reminderRepo);

        for (BotTask task : result.getTasks()) {
            task.setCallbackQueryId(result.getCallbackQueryId());

            if (task.isResolveByRole()) {
                HashMap<Long, Integer> resolved = new HashMap<>();
                if (task.isResolveByRole()) {
                    List<Long> chatIds = projectRepo.getUsersByRoleAndTableName(task.getProject().getTableName(), task.getRole());
                    for (long chatId : chatIds) {
                        resolved.put(chatId, 0);
                    }
                } else {
                    List<Long> chatIds = userRepo.findChatIdsByRole(task.getRole());
                    for (long chatId : chatIds) {
                        resolved.put(chatId, 0);
                    }
                }
                task.setChatMessageIds(resolved);
            }

            executeTask(task);

            if (task.isResolveByRole()) {
                saveMessageIds(paper, task);
            }
        }

        // Ответ на callback (если не было ANSWER_CALLBACK задачи — пустой ответ для снятия loading spinner)
        if (result.getCallbackQueryId() != null) {
            boolean hasAnswer = result.getTasks().stream()
                    .anyMatch(t -> t.getTypeOfTask() == BotTask.Type.ANSWER_CALLBACK);
            if (!hasAnswer) {
                try {
                    api.answerCallbackQuery(result.getCallbackQueryId(), null, false);
                } catch (Exception e) {
                    log.debug("Не удалось отправить пустой answerCallbackQuery: {}", e.getMessage());
                }
            }
        }
    }

    public void executeTask(BotTask task) {
        String markupJson = task.getKeyboard().toJson();

        try {
            switch (task.getTypeOfTask()) {
                case SEND_TEXT -> {
                    for (long chatId : task.getChatMessageIds().keySet()) {
                        int mesId = sendTextChunked(chatId, task.getText(), markupJson, task.getReplyToMessageId());
                        task.getChatMessageIds().put(chatId, mesId);
                    }
                }
                case SEND_PHOTO -> {
                    for (long chatId : task.getChatMessageIds().keySet()) {
                        int mesId = api.sendPhoto(chatId, task.getFileId(), task.getText(), markupJson);
                        task.getChatMessageIds().put(chatId, mesId);
                    }
                }
                case SEND_DOCUMENT -> {
                    for (long chatId : task.getChatMessageIds().keySet()) {
                        int mesId;
                        if (task.getFilePath() != null) {
                            mesId = api.sendDocumentFile(chatId, new java.io.File(task.getFilePath()), task.getText(), markupJson);
                        } else {
                            mesId = api.sendDocument(chatId, task.getFileId(), task.getText(), markupJson);
                        }
                        task.getChatMessageIds().put(chatId, mesId);
                    }
                }
                case EDIT_CAPTION -> {
                    // editMessageCaption требует InlineKeyboardMarkup, remove_keyboard здесь недопустим
                    String inlineMarkupJson = toInlineMarkupJson(markupJson);
                    for (HashMap.Entry<Long, Integer> entry : task.getChatMessageIds().entrySet()) {
                        api.editCaption(entry.getKey(), entry.getValue(), task.getText(), inlineMarkupJson);
                    }
                }
                case EDIT_MARKUP -> {
                    // editMessageReplyMarkup требует InlineKeyboardMarkup
                    String inlineMarkupJson = toInlineMarkupJson(markupJson);
                    for (HashMap.Entry<Long, Integer> entry : task.getChatMessageIds().entrySet()) {
                        api.editMarkup(entry.getKey(), entry.getValue(), inlineMarkupJson);
                    }
                }
                case ANSWER_CALLBACK -> {
                    if (task.getCallbackQueryId() != null) {
                        api.answerCallbackQuery(task.getCallbackQueryId(), task.getText(), true);
                    }
                }
                case ANSWER_INLINE_QUERY -> {
                    if (task.getInlineQueryId() != null && task.getTiles().getTilesJson() != null) {
                        api.answerInlineQuery(task.getInlineQueryId(), task.getTiles().getTilesJson());
                    }
                }
            }
        } catch (TelegramApiException e) {
            log.error("Ошибка Telegram API при выполнении {} → {}: HTTP {} — {}",
                    task.getTypeOfTask(), task.getChatMessageIds().keySet(),
                    e.getErrorCode(), e.getDescription());
        } catch (Exception e) {
            log.error("Ошибка при выполнении задачи {} → {}",
                    task.getTypeOfTask(), task.getChatMessageIds().keySet(), e);
        }
    }

    private void saveMessageIds(Paper paper, BotTask task) {
        Role role = task.getRole();
        HashMap<Long, Integer> ids = task.getChatMessageIds();

        if (role == Role.APPROVER) {
            paper.setApprovers(ids);
        } else if (role == Role.REGISTRAR) {
            paper.setRegistrars(ids);
        } else if (role == Role.RECEIVER) {
            paper.setReceivers(ids);
        }

        paperRepo.save(paper);
    }

    /**
     * editMessageCaption / editMessageReplyMarkup принимают ТОЛЬКО InlineKeyboardMarkup.
     * Если пришёл remove_keyboard — заменяем на пустой inline (убирает кнопки без ошибки).
     */
    private String toInlineMarkupJson(String markupJson) {
        if (markupJson == null || markupJson.isBlank()) return bot.model.Keyboard.EMPTY_INLINE_JSON;
        if (markupJson.contains("remove_keyboard")) return bot.model.Keyboard.EMPTY_INLINE_JSON;
        return markupJson;
    }

    private static final int MAX_TELEGRAM_MSG_LEN = 4000;

    /**
     * Отправляет текст, разбивая на части до 4000 символов, если текст превышает лимит Telegram (4096).
     * Разметка (markup) прикрепляется только к последней части.
     * Возвращает id последнего отправленного сообщения.
     */
    private int sendTextChunked(long chatId, String text, String markupJson, int replyToMessageId) throws Exception {
        if (text == null || text.length() <= MAX_TELEGRAM_MSG_LEN) {
            return api.sendText(chatId, text, markupJson, replyToMessageId);
        }

        List<String> chunks = splitTextIntoChunks(text, MAX_TELEGRAM_MSG_LEN);
        int lastMesId = 0;
        for (int i = 0; i < chunks.size(); i++) {
            boolean isLast = (i == chunks.size() - 1);
            String chunkMarkup = isLast ? markupJson : null;
            int replyId = (i == 0) ? replyToMessageId : 0;
            lastMesId = api.sendText(chatId, chunks.get(i), chunkMarkup, replyId);
        }
        return lastMesId;
    }

    private static List<String> splitTextIntoChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                while (line.length() > maxLen) {
                    chunks.add(line.substring(0, maxLen));
                    line = line.substring(maxLen);
                }
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
