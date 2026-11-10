package bot.model;

import bot.keyboard.KeyboardBuilder;

import java.util.HashMap;
import java.util.List;

public class BotTask {
    public enum Type { SEND_TEXT, SEND_PHOTO, SEND_DOCUMENT, EDIT_CAPTION, EDIT_TEXT, EDIT_MARKUP, ANSWER_CALLBACK, ANSWER_INLINE_QUERY }

    private Type typeOfTask;
    private String text;
    private String fileId;
    private String filePath; // путь к файлу на диске для отправки нового файла
    private HashMap<Long, Integer> chatMessageIds;
    private TilesList tiles;
    private Keyboard markup;
    private String markupJson; // для ReplyKeyboard, сериализованный заранее
    private boolean resolveByRole;
    private boolean resolveByProject;
    private Role role;
    private Project project;
    private int replyToMessageId;
    private String callbackQueryId;
    private String inlineQueryId;

    public BotTask(Type typeOfTask, HashMap<Long, Integer> chatMessageIds, String text, String fileId, Keyboard markup) {
        this.typeOfTask = typeOfTask;
        this.chatMessageIds = chatMessageIds;
        this.text = text;
        this.fileId = fileId;
        if (markup != null)
            this.markup = markup;
        else
            this.markup = KeyboardBuilder.removeKeyboard();
        this.resolveByRole = false;
    }

    public BotTask(Type typeOfTask, long chatId, int mesId, String text, String fileId, Keyboard markup) {
        this.typeOfTask = typeOfTask;
        HashMap<Long, Integer> map = new HashMap<>();
        map.put(chatId, mesId);
        this.chatMessageIds = map;
        this.text = text;
        this.fileId = fileId;
        if (markup != null)
            this.markup = markup;
        else
            this.markup = KeyboardBuilder.removeKeyboard();
        this.resolveByRole = false;
    }

    public BotTask(Type typeOfTask, long chatId, String text, String fileId, Keyboard markup) {
        if (!typeOfTask.toString().contains("SEND") && typeOfTask != Type.ANSWER_CALLBACK) {
            throw new IllegalArgumentException("Используйте конструктор с chatMessageIds/chatId + mesId для EDIT типов");
        }
        this.typeOfTask = typeOfTask;
        HashMap<Long, Integer> map = new HashMap<>();
        map.put(chatId, 0);
        this.chatMessageIds = map;
        this.text = text;
        this.fileId = fileId;
        if (markup != null)
            this.markup = markup;
        else
            this.markup = KeyboardBuilder.removeKeyboard();
        this.resolveByRole = false;
    }

    public BotTask(Type typeOfTask, Role role, Project project, String text, String fileId, Keyboard markup) {
        this.typeOfTask = typeOfTask;
        this.role = role;
        this.project = project;
        this.text = text;
        this.fileId = fileId;
        if (markup != null)
            this.markup = markup;
        else
            this.markup = KeyboardBuilder.removeKeyboard();
        this.resolveByRole = true;
        this.resolveByProject = true;
    }

    public BotTask(Type typeOfTask, String inlineQueryId, TilesList tiles) {
        this.typeOfTask = typeOfTask;
        this.tiles = tiles;
        this.inlineQueryId = inlineQueryId;
    }

    // Getters
    public Type getTypeOfTask() { return typeOfTask; }
    public String getText() { return text; }
    public String getFileId() { return fileId; }
    public String getFilePath() { return filePath; }
    public HashMap<Long, Integer> getChatMessageIds() { return chatMessageIds; }
    public Keyboard getKeyboard() {
        if (markup == null)
            return new Keyboard(true);
        else
            return markup;
    }
    public String getMarkupJson() { return markupJson; }
    public boolean isResolveByRole() { return resolveByRole; }
    public boolean isResolveByProject() { return resolveByProject; }
    public Role getRole() { return role; }
    public int getReplyToMessageId() { return replyToMessageId; }
    public String getCallbackQueryId() { return callbackQueryId; }
    public TilesList getTiles() { return tiles; }
    public String getInlineQueryId() { return inlineQueryId; }
    public Project getProject() { return project; }

    // Setters
    public void setTypeOfTask(Type typeOfTask) { this.typeOfTask = typeOfTask; }
    public void setText(String text) { this.text = text; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setChatMessageIds(HashMap<Long, Integer> chatMessageIds) { this.chatMessageIds = chatMessageIds; }
    public void setMarkup(Keyboard markup) { this.markup = markup; }
    public void setMarkupJson(String markupJson) { this.markupJson = markupJson; }
    public void setResolveByRole(boolean resolveByRole) { this.resolveByRole = resolveByRole; }
    public void setRole(Role role) { this.role = role; }
    public void setReplyToMessageId(int replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public void setCallbackQueryId(String callbackQueryId) { this.callbackQueryId = callbackQueryId; }
}
