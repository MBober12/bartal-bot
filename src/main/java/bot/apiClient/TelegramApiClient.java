package bot.apiClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TelegramApiClient {
    private static final String API_URL = "https://api.telegram.org";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String botToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TelegramApiClient(OkHttpClient httpClient, String botToken) {
        this.httpClient = httpClient;
        this.botToken = botToken;
    }

    // -------------------------------------------------------------------------
    // 1. Отправка сообщений (возвращают message_id)
    // -------------------------------------------------------------------------

    public int sendText(long chatId, String text) {
        return sendText(chatId, text, null, 0);
    }

    public int sendText(long chatId, String text, String markupJson) {
        return sendText(chatId, text, markupJson, 0);
    }

    public int sendText(long chatId, String text, String markupJson, int replyToMessageId) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        if (replyToMessageId > 0) body.put("reply_to_message_id", replyToMessageId);
        return post("sendMessage", body);
    }

    public int sendReply(long chatId, int replyToMessageId, String text) {
        return sendText(chatId, text, null, replyToMessageId);
    }

    public int sendPhoto(long chatId, String fileId, String caption, String markupJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", fileId);
        body.put("parse_mode", "HTML");
        if (caption != null) body.put("caption", caption);
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        return post("sendPhoto", body);
    }

    public int sendDocument(long chatId, String fileId, String caption, String markupJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("document", fileId);
        body.put("parse_mode", "HTML");
        if (caption != null) body.put("caption", caption);
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        return post("sendDocument", body);
    }

    public int sendDocumentFile(long chatId, File file, String caption, String markupJson) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("document", file.getName(),
                        RequestBody.create(file, MediaType.parse("application/octet-stream")))
                .addFormDataPart("parse_mode", "HTML");
        if (caption != null) builder.addFormDataPart("caption", caption);
        if (markupJson != null && !markupJson.isBlank()) builder.addFormDataPart("reply_markup", markupJson);

        Request request = new Request.Builder()
                .url(API_URL + "/bot" + botToken + "/sendDocument")
                .post(builder.build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonNode root = parseSuccessfulResponse("sendDocument", response.code(), response.isSuccessful(), responseBody,
                    "file:" + file.getName());
            return parseMessageId(root, "sendDocument", response.code());
        } catch (IOException e) {
            throw new TelegramApiException("sendDocument", e);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Получение файлов
    // -------------------------------------------------------------------------

    /**
     * Возвращает InputStream файла по file_id.
     * ВАЖНО: вызывающий код обязан закрыть поток после использования.
     */
    public InputStream getDocumentFile(String fileId) {
        String jsonResponse = get("getFile?file_id=" + fileId);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String filePath = root.path("result").path("file_path").asText();
            if (filePath.isBlank()) {
                throw new TelegramApiException("getFile", 200, "Telegram response did not include result.file_path", null);
            }
            String downloadUrl = API_URL + "/file/bot" + botToken + "/" + filePath;
            Request request = new Request.Builder().url(downloadUrl).build();
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                String body;
                try (response) {
                    body = response.body() != null ? response.body().string() : "";
                }
                throw new TelegramApiException("downloadFile", response.code(), parseDescription(body), null);
            }
            if (response.body() == null) {
                response.close();
                throw new TelegramApiException("downloadFile", response.code(), "Empty response body", null);
            }
            return new java.io.FilterInputStream(response.body().byteStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
        } catch (IOException e) {
            throw new TelegramApiException("getDocumentFile", e);
        }
    }

    // -------------------------------------------------------------------------
    // 3. Редактирование
    // -------------------------------------------------------------------------

    public void editCaption(long chatId, int messageId, String caption, String markupJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("parse_mode", "HTML");
        if (caption != null) body.put("caption", caption);
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        post("editMessageCaption", body);
    }

    public void editText(long chatId, int messageId, String text, String markupJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        post("editMessageText", body);
    }

    public void editMarkup(long chatId, int messageId, String markupJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        if (markupJson != null && !markupJson.isBlank()) body.put("reply_markup", parseRawJson(markupJson));
        post("editMessageReplyMarkup", body);
    }

    // -------------------------------------------------------------------------
    // 4. Callback & Inline
    // -------------------------------------------------------------------------

    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        Map<String, Object> body = new HashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) body.put("text", text);
        if (showAlert) body.put("show_alert", true);
        post("answerCallbackQuery", body);
    }

    /**
     * @param tilesJson JSON-объекты тайлов через запятую, БЕЗ внешних скобок []
     *                  (так возвращает TilesList.getTilesJson())
     */
    public void answerInlineQuery(String inlineQueryId, String tilesJson) {
        Map<String, Object> body = new HashMap<>();
        body.put("inline_query_id", inlineQueryId);
        body.put("is_personal", true);
        body.put("cache_time", 0);
        if (tilesJson != null && !tilesJson.isBlank()) {
            body.put("results", parseRawJson("[" + tilesJson + "]"));
        } else {
            body.put("results", List.of());
        }
        post("answerInlineQuery", body);
    }

    // -------------------------------------------------------------------------
    // 5. Внутренние методы
    // -------------------------------------------------------------------------

    private String get(String method) {
        Request request = new Request.Builder()
                .url(API_URL + "/bot" + botToken + "/" + method)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return parseSuccessfulResponse(method, response.code(), response.isSuccessful(), body, null).toString();
        } catch (IOException e) {
            throw new TelegramApiException(method, e);
        }
    }

    private int post(String method, Map<String, Object> bodyMap) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(bodyMap);
        } catch (Exception e) {
            throw new TelegramApiException(method, e);
        }

        Request request = new Request.Builder()
                .url(API_URL + "/bot" + botToken + "/" + method)
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonNode root = parseSuccessfulResponse(method, response.code(), response.isSuccessful(), responseBody, jsonBody);
            if (method.equals("sendMessage") || method.equals("sendPhoto") || method.equals("sendDocument")) {
                return parseMessageId(root, method, response.code());
            }
            return 0;
        } catch (IOException e) {
            throw new TelegramApiException(method, e);
        }
    }

    private int parseMessageId(JsonNode root, String method, int statusCode) {
        JsonNode messageId = root.path("result").path("message_id");
        if (!messageId.canConvertToInt() || messageId.asInt() <= 0) {
            throw new TelegramApiException(method, statusCode,
                    "Telegram response did not include a valid result.message_id", null);
        }
        return messageId.asInt();
    }

    private JsonNode parseSuccessfulResponse(String method, int statusCode, boolean httpSuccessful,
                                             String body, String requestBody) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!httpSuccessful || !root.path("ok").asBoolean(false)) {
                throw new TelegramApiException(method, statusCode, parseDescription(root, body), requestBody);
            }
            return root;
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TelegramApiException(method, statusCode,
                    "Invalid Telegram API response: " + e.getMessage(), requestBody);
        }
    }

    private String parseDescription(String body) {
        try {
            return parseDescription(objectMapper.readTree(body), body);
        } catch (Exception e) {
            return body;
        }
    }

    private String parseDescription(JsonNode root, String body) {
        return root.path("description").asText(body);
    }

    private Object parseRawJson(String jsonString) {
        try {
            return objectMapper.readValue(jsonString, Object.class);
        } catch (Exception e) {
            return jsonString;
        }
    }
}
