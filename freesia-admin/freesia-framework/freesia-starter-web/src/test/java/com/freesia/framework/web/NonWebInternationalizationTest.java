package com.freesia.framework.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = NonWebInternationalizationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.messages.basename=i18n/sample/messages")
class NonWebInternationalizationTest {
    @Autowired MessageSource messages;

    @Test
    void backgroundWorkUsesTheExplicitLocaleWithoutAnyHttpRequest() {
        var english = CompletableFuture.supplyAsync(
                () -> messages.getMessage("common.error.forbidden", null, Locale.ENGLISH));
        var chinese = CompletableFuture.supplyAsync(
                () -> messages.getMessage("common.error.forbidden", null, Locale.CHINESE));

        assertThat(english.join()).isEqualTo("Access denied");
        assertThat(chinese.join()).isEqualTo("没有访问权限");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
