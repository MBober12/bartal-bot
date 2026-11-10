package bot.model;

public class TextResult {
    public TextResult() {
    }

    public static DocResult build(long chatId, String text) {
        return build(chatId, text, new Keyboard(true));
    }

    public static DocResult build(long chatId, String text, Keyboard markup) {
        DocResult result = new DocResult();
        BotTask task = new BotTask(BotTask.Type.SEND_TEXT, chatId, text, null, markup);
        result.addTask(task);
        return result;
    }
}