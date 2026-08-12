package com.example.attend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OperationalTelegramPropertiesTest {

    @Test
    void defaultsDeliveryRecoveryToSixtySeconds() {
        OperationalTelegramProperties properties =
                new OperationalTelegramProperties(false, null, 0, 0);

        assertThat(properties.dispatchFixedDelayMs()).isEqualTo(60_000);
    }

    @Test
    void inactiveEnvironmentExampleUsesBindableEmptyDefaults() throws Exception {
        String example = Files.readString(Path.of(".env.example"));

        assertThat(example).contains(
                "OPERATIONS_TELEGRAM_ENABLED=false\n",
                "OPERATIONS_TELEGRAM_BOT_TOKEN=\n",
                "OPERATIONS_TELEGRAM_CHAT_ID=0\n");
    }
}
