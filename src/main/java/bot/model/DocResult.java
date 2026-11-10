package bot.model;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DocResult {
    private int docId;
    private final List<BotTask> tasks = new ArrayList<>();

    private String callbackQueryId;

    public void setCallbackQueryId(String callbackQueryId) {
        this.callbackQueryId = callbackQueryId;
    }

    public String getCallbackQueryId() {
        return callbackQueryId;
    }

    public void setDocId(int docId) {
        this.docId = docId;
    }

    public int getDocId() {
        return docId;
    }

    public void addTask(BotTask task) {
        tasks.add(task);
    }

    public List<BotTask> getTasks() {
        return tasks;
    }
}
