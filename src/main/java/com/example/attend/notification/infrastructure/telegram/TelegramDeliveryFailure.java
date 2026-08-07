package com.example.attend.notification.infrastructure.telegram;

/** Telegram 전달 결과를 재시도 가능 여부로 분류한 예외다. */
public final class TelegramDeliveryFailure extends RuntimeException {
    private final boolean permanent;
    private final Integer retryAfterSeconds;
    private final String safeCode;

    public TelegramDeliveryFailure(
            boolean permanent, Integer retryAfterSeconds, String safeCode, Throwable cause) {
        super(safeCode, cause);
        this.permanent = permanent;
        this.retryAfterSeconds = retryAfterSeconds;
        this.safeCode = safeCode;
    }

    public boolean permanent() { return permanent; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
    public String safeCode() { return safeCode; }
}
