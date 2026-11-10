package bot.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TilesList {

    private static final ObjectMapper mapper = new ObjectMapper();

    private List<InlineQueryTile> tiles = new ArrayList<>();

    public TilesList() {}

    public TilesList(List<InlineQueryTile> tiles) {
        this.tiles = tiles;
    }

    public void addTile(InlineQueryTile tile) {
        tiles.add(tile);
    }

    /**
     * Возвращает JSON-массив тайлов без обрамляющих скобок [...],
     * т.к. TelegramApiClient сам оборачивает в массив через parseRawJson("[" + tilesJson + "]").
     */
    public String getTilesJson() {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < tiles.size(); i++) {
                InlineQueryTile tile = tiles.get(i);
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("type", tile.getType());
                obj.put("id", String.valueOf(i));
                obj.put("title", tile.getTitle());
                obj.put("description", tile.getDescription());
                obj.put("input_message_content", Map.of(
                        "message_text", tile.getMessageText()
                ));
                list.add(obj);
            }
            // Убираем внешние [ ] — клиент добавляет их сам
            String full = mapper.writeValueAsString(list);
            return full.substring(1, full.length() - 1);
        } catch (Exception e) {
            throw new RuntimeException("getTilesJson failed", e);
        }
    }
}
