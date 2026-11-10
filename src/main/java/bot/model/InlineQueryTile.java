package bot.model;

public class InlineQueryTile {
    private final String type;
    private final String title;
    private final String description;
    private final String messageText;

    public InlineQueryTile(String type, String title, String description, String messageText) {
        this.type = type;
        this.title = title;
        this.description = description;
        this.messageText = messageText;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getMessageText() {
        return messageText;
    }
}
