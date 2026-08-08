package com.example.attend.notification.infrastructure.telegram;

/** Telegram 전달 결과를 재시도 가능 여부로 분류한 예외다. */
public final class TelegramDeliveryFailure extends RuntimeException {
    private final boolean permanent;
    private final boolean revokeConnection;
    private final Integer retryAfterSeconds;
    private final String safeCode;

    public TelegramDeliveryFailure(
            boolean permanent,
            boolean revokeConnection,
            Integer retryAfterSeconds,
            String safeCode,
            Throwable cause) {
        super(safeCode, cause);
        this.permanent = permanent;
        this.revokeConnection = revokeConnection;
        this.retryAfterSeconds = retryAfterSeconds;
        this.safeCode = safeCode;
    }

    public boolean permanent() { return permanent; }
    /** Only a Telegram response tied to this private chat may remove a link. */
    public boolean revokeConnection() { return revokeConnection; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public String safeCode() { return safeCode; }
}
