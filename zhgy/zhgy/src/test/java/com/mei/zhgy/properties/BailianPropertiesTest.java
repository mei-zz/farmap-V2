package com.mei.zhgy.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BailianPropertiesTest {
    @Test
    void normalizesRootAndFullChatCompletionUrls() {
        BailianProperties properties = new BailianProperties();

        properties.getApi().setUrl("https://example.test/v1/");
        assertEquals("https://example.test/v1/chat/completions", properties.getChatCompletionsUrl());

        properties.getApi().setUrl("https://example.test/v1/chat/completions/");
        assertEquals("https://example.test/v1/chat/completions", properties.getChatCompletionsUrl());
    }

    @Test
    void clampsRetrySettingsToFiniteBounds() {
        BailianProperties properties = new BailianProperties();

        properties.getApi().setMaxRetries(99);
        properties.getApi().setRetryBackoffMs(999999);
        assertEquals(2, properties.getMaxRetries());
        assertEquals(5000, properties.getRetryBackoffMs());

        properties.getApi().setMaxRetries(-1);
        properties.getApi().setRetryBackoffMs(-1);
        assertEquals(0, properties.getMaxRetries());
        assertEquals(0, properties.getRetryBackoffMs());
    }
}
