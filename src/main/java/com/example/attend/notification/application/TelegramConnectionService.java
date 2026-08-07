package com.example.attend.notification.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.TelegramConnectionRow;
import com.example.attend.notification.domain.TelegramLinkTokenRow;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인 계정 본인의 Telegram 연결 token과 연결 상태를 관리한다. */
@Service
public class TelegramConnectionService {
    private static final Duration LINK_TTL = Duration.ofMinutes(10);
    private final TelegramProperties properties;
    private final AdminWriteGate writeGate;
    private final TelegramNotificationMapper mapper;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TelegramConnectionService(
            TelegramProperties properties,
            AdminWriteGate writeGate,
            TelegramNotificationMapper mapper,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.properties = properties;
        this.writeGate = writeGate;
        this.mapper = mapper;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TelegramConnectionView view(long accountId) {
        if (!properties.enabled()) {
            return new TelegramConnectionView("DISABLED", null, null, null);
        }
        TelegramConnectionRow connection = mapper.selectConnection(accountId);
        if (connection != null) {
            return new TelegramConnectionView(
                    "LINKED", connection.linkedAt(), null, mapper.selectLatestTestStatus(accountId));
        }
        Instant expiry = mapper.selectActiveLinkTokenExpiry(accountId);
        return new TelegramConnectionView(
                expiry == null ? "UNLINKED" : "LINK_PENDING", null, expiry,
                mapper.selectLatestTestStatus(accountId));
    }

    /** 새 raw deep-link token은 호출자에게 한 번만 반환한다. */
    @Transactional
    public String issueLink(long accountId) {
        requireEnabledAndWritable();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        mapper.revokeActiveLinkTokens(accountId, now);
        mapper.insertLinkToken(accountId, hmac(rawToken), now, now.plus(LINK_TTL));
        return "https://t.me/" + properties.botUsername() + "?start=" + rawToken;
    }

    @Transactional
    public void disconnect(long accountId) {
        requireEnabledAndWritable();
        Instant now = clock.instant();
        mapper.deleteConnection(accountId);
        mapper.revokeActiveLinkTokens(accountId, now);
        mapper.cancelPendingAccountOutbox(accountId, now);
        auditLogWriter.writeAccount(null, new AccountActor(accountId), null,
                "TELEGRAM_DISCONNECTED", "ACCOUNT", Long.toString(accountId),
                Map.of("telegramConnected", true), Map.of("telegramConnected", false), null);
    }

    @Transactional
    public void requestTestMessage(long accountId) {
        requireEnabledAndWritable();
        if (mapper.selectConnection(accountId) == null) {
            throw new IllegalStateException("Telegram is not connected");
        }
        mapper.insertTestOutbox(accountId,
                "[시험] 출석 알림 Telegram 연결이 정상입니다.\n실제 출석 정보는 포함되지 않습니다.");
    }

    /** Telegram webhook에서 검증된 개인 채팅의 /start token을 소비한다. */
    @Transactional
    public boolean consumeStart(long updateId, String rawToken, long chatId, long telegramUserId) {
        if (!properties.enabled() || rawToken == null || rawToken.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        if (mapper.insertWebhookUpdate(updateId, now) != 1) {
            return false;
        }
        TelegramLinkTokenRow token = mapper.lockLinkToken(hmac(rawToken));
        if (token == null || !now.isBefore(token.expiresAt())) {
            return false;
        }
        try {
            mapper.upsertConnection(token.accountId(), chatId, telegramUserId, now);
        } catch (DuplicateKeyException exception) {
            return false;
        }
        if (mapper.consumeLinkToken(token.id(), now) != 1) {
            return false;
        }
        auditLogWriter.writeAccount(null, new AccountActor(token.accountId()), null,
                "TELEGRAM_CONNECTED", "ACCOUNT", Long.toString(token.accountId()),
                Map.of("telegramConnected", false), Map.of("telegramConnected", true), null);
        return true;
    }

    private void requireEnabledAndWritable() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Telegram notifications are disabled");
        }
		if (properties.botUsername() == null || properties.linkTokenPepper() == null) {
			throw new IllegalStateException("Telegram notification settings are incomplete");
		}
        writeGate.requireEnabled();
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.linkTokenPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Telegram link token hashing is unavailable", exception);
        }
    }
}
