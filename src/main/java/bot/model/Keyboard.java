package bot.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Keyboard {
    private final Button[][] keyboard;
    private final KeyboardType type;
    private boolean isRemoveKeyboard;

    private static final ObjectMapper mapper = new ObjectMapper();
    // JSON для снятия inline-клавиатуры с медиа-сообщения (editMessageCaption/editMarkup)
    public static final String EMPTY_INLINE_JSON = "{\"inline_keyboard\":[[]]}";

    public Keyboard(KeyboardType type, Button[][] keyboard) {
        this.type = type;
        this.keyboard = keyboard;
    }

    public Keyboard(boolean isRemoveKeyboard) {
        this.isRemoveKeyboard = isRemoveKeyboard;
        this.keyboard = null;
        this.type = null;
    }

    public String toJson() {
        try {
            if (isRemoveKeyboard) {
                return removeKeyboard();
            } else if (type == null && keyboard == null) {
                return enterInlineMode();
            } else if (type == KeyboardType.INLINE) {
                return inlineToJson();
            } else if (type == KeyboardType.REPLY) {
                return replyToJson();
            }
            return removeKeyboard();
        } catch (Exception e) {
            throw new RuntimeException("Keyboard.toJson() failed", e);
        }
    }

    // === ReplyKeyboardMarkup ===

    private String replyToJson() throws Exception {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (Button[] row : keyboard) {
            List<Map<String, Object>> rowList = new ArrayList<>();
            for (Button btn : row) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("text", btn.getText());
                rowList.add(b);
            }
            rows.add(rowList);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyboard", rows);
        result.put("resize_keyboard", true);
        return mapper.writeValueAsString(result);
    }

    // === InlineKeyboardMarkup ===

    private String inlineToJson() throws Exception {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (Button[] row : keyboard) {
            List<Map<String, Object>> rowList = new ArrayList<>();
            for (Button btn : row) {
                rowList.add(buttonToMap(btn));
            }
            rows.add(rowList);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inline_keyboard", rows);
        return mapper.writeValueAsString(result);
    }

    // === static helpers ===

    public static String replyKeyboard(String[]... rows) {
        try {
            List<List<Map<String, Object>>> keyboard = new ArrayList<>();
            for (String[] row : rows) {
                List<Map<String, Object>> rowList = new ArrayList<>();
                for (String text : row) {
                    rowList.add(Map.of("text", text));
                }
                keyboard.add(rowList);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("keyboard", keyboard);
            result.put("resize_keyboard", true);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("replyKeyboard failed", e);
        }
    }

    public static String removeKeyboard() {
        return "{\"remove_keyboard\":true}";
    }

    /** Убирает inline-клавиатуру с медиа-сообщения (editMessageCaption/editMarkup) */
    public static String removeInlineKeyboard() {
        return EMPTY_INLINE_JSON;
    }

    private static String enterInlineMode() {
        try {
            Map<String, Object> btn = new LinkedHashMap<>();
            btn.put("text", "\uD83D\uDD0D Выбрать проект из доступных");
            btn.put("switch_inline_query_current_chat", "");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("inline_keyboard", List.of(List.of(btn)));
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("enterInlineMode failed", e);
        }
    }

    // === Вспомогательные ===

    private Map<String, Object> buttonToMap(Button btn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", btn.getText());
        if (btn.getCallback() != null) {
            map.put("callback_data", btn.getCallback());
        }
        if (btn.getColorToString() != null) {
            map.put("style", btn.getColorToString());
        }
        return map;
    }

    public Button[][] getKeyboard() {
        return keyboard;
    }

    public KeyboardType getType() {
        return type;
    }

    public enum KeyboardType {
        INLINE,
        REPLY
    }
}
