package bot.apiClient;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фейковый TelegramApiClient для нагрузочного и интеграционного тестирования без обращения к серверам Telegram.
 */
public class MockTelegramApiClient extends TelegramApiClient {
    private static final Logger log = LoggerFactory.getLogger(MockTelegramApiClient.class);

    private final AtomicInteger messageIdCounter = new AtomicInteger(1000);
    private final ConcurrentLinkedQueue<SentCall> sentCalls = new ConcurrentLinkedQueue<>();

    public record SentCall(String method, long chatId, int messageId, String text, String fileId) {}

    public MockTelegramApiClient() {
        super(new OkHttpClient(), "MOCK_TOKEN");
    }

        public ConcurrentLinkedQueue<SentCall> getSentCalls() {
        return sentCalls;
    }

    public int getSentCount() {
        return sentCalls.size();
    }

    public void clearHistory() {
        sentCalls.clear();
    }

    @Override
    public int sendText(long chatId, String text, String markupJson, int replyToMessageId) {
        int id = messageIdCounter.incrementAndGet();
        sentCalls.add(new SentCall("sendText", chatId, id, text, null));
        return id;
    }

    @Override
    public int sendPhoto(long chatId, String fileId, String caption, String markupJson) {
        int id = messageIdCounter.incrementAndGet();
        sentCalls.add(new SentCall("sendPhoto", chatId, id, caption, fileId));
        return id;
    }

    @Override
    public int sendDocument(long chatId, String fileId, String caption, String markupJson) {
        int id = messageIdCounter.incrementAndGet();
        sentCalls.add(new SentCall("sendDocument", chatId, id, caption, fileId));
        return id;
    }

    @Override
    public int sendDocumentFile(long chatId, File file, String caption, String markupJson) {
        int id = messageIdCounter.incrementAndGet();
        sentCalls.add(new SentCall("sendDocumentFile", chatId, id, caption, file.getName()));
        return id;
    }

    @Override
    public InputStream getDocumentFile(String fileId) {
        return new ByteArrayInputStream("fake_content".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void editCaption(long chatId, int messageId, String caption, String markupJson) {
        sentCalls.add(new SentCall("editCaption", chatId, messageId, caption, null));
    }

    @Override
    public void editText(long chatId, int messageId, String text, String markupJson) {
        sentCalls.add(new SentCall("editText", chatId, messageId, text, null));
    }

    @Override
    public void editMarkup(long chatId, int messageId, String markupJson) {
        sentCalls.add(new SentCall("editMarkup", chatId, messageId, null, null));
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        sentCalls.add(new SentCall("answerCallbackQuery", 0, 0, text, callbackQueryId));
    }

    @Override
    public void answerInlineQuery(String inlineQueryId, String tilesJson) {
        sentCalls.add(new SentCall("answerInlineQuery", 0, 0, tilesJson, inlineQueryId));
    }
}
