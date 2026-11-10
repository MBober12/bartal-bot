package bot.model;

import bot.keyboard.KeyboardBuilder;

public class Button {
    public Button(String text, String callback, ColorOfButton color) {
        this.text = text;
        this.callback = callback;
        this.color = color;
    }
    public Button(String text) {
        this.text = text;
    }
    public Button(String text, Stage stage, int docId, ColorOfButton color) {
        this.text = text;
        this.callback = stage.toString() + "_" + docId;
        this.color = color;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setCallback(String callback) {
        this.callback = callback;
    }

    public void setColor(ColorOfButton color) {
        this.color = color;
    }

    public String getText() {
        return text;
    }

    public String getCallback() {
        return callback;
    }

    public String getColorToString() {
        return switch (color) {
            case RED -> "danger";
            case GREEN -> "success";
            case BLUE -> "primary";
        };
    }

    private String text;
    private String callback;
    private ColorOfButton color;

    public static enum ColorOfButton {
        GREEN,
        RED,
        BLUE
    }
}
