package ai.myvocal.multiwss;

public class TTSException extends Exception {
    private final ConnectionErrorCategory category;
    private final Integer httpStatus;
    private final String errorCode;

    public TTSException(ConnectionErrorCategory category, String message) {
        this(category, null, null, message, null);
    }

    public TTSException(ConnectionErrorCategory category, Integer httpStatus, String message) {
        this(category, httpStatus, null, message, null);
    }

    public TTSException(
            ConnectionErrorCategory category,
            Integer httpStatus,
            String message,
            Throwable cause) {
        this(category, httpStatus, null, message, cause);
    }

    public TTSException(
            ConnectionErrorCategory category,
            Integer httpStatus,
            String errorCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.category = category;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ConnectionErrorCategory getCategory() {
        return category;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

