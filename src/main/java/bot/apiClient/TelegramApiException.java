package bot.apiClient;

public class TelegramApiException extends RuntimeException {
    private final String method;
    private final int errorCode;
    private final String description;
    private final String requestBody;

    public TelegramApiException(String method, int errorCode, String description, String requestBody) {
        super("[" + method + "] HTTP " + errorCode + ": " + description);
        this.method = method;
        this.errorCode = errorCode;
        this.description = description;
        this.requestBody = requestBody;
    }

    public TelegramApiException(String method, Exception cause) {
        super("[" + method + "] " + cause.getMessage(), cause);
        this.method = method;
        this.errorCode = 0;
        this.description = cause.getMessage();
        this.requestBody = null;
    }

    public String getMethod()      { return method; }
    public int getErrorCode()      { return errorCode; }
    public String getDescription() { return description; }
    public String getRequestBody() { return requestBody; }
}
