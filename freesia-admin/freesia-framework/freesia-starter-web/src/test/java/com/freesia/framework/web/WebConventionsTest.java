package com.freesia.framework.web;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.error.ErrorCode;
import com.freesia.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.MessageSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = WebConventionsTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.messages.basename=i18n/sample/messages", "spring.messages.fallback-to-system-locale=false"})
class WebConventionsTest {
    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired MessageSource messageSource;

    @Test
    void uuidsAreStringsWhileBoxedAndPrimitiveCountsStayNumericAndNullableFieldsAndUtcInstantsArePreserved() throws Exception {
        var response = get("/sample", "zh");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "{\"code\":200,\"msg\":\"OK\",\"data\":{\"id\":\"019aa220-6c88-7000-8000-000000000001\",\"parentId\":null,\"boxedCount\":42,\"count\":42,\"boxedTotal\":7,\"total\":7,\"optionalCount\":null,\"createdAt\":\"2026-11-18T03:04:05Z\"}}");
    }

    @Test
    void businessErrorsKeepTheirCodeUseTheirHttpStatusAndFollowTheRequestLanguage() throws Exception {
        var chinese = get("/quota", "zh");
        var english = get("/quota", "en");
        var unsupported = get("/quota", "fr");
        var unspecified = client.send(HttpRequest.newBuilder(uri("/quota")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(chinese.statusCode()).isEqualTo(409);
        assertThat(chinese.body()).isEqualTo("{\"code\":13001,\"msg\":\"配额已用完：5/5\",\"data\":null}");
        assertThat(english.body()).isEqualTo("{\"code\":13001,\"msg\":\"Quota exhausted: 5/5\",\"data\":null}");
        assertThat(unsupported.body()).isEqualTo(chinese.body());
        assertThat(unspecified.body()).isEqualTo(chinese.body());
    }

    @Test
    void languageOnlyChineseAndWeightedRegionalPreferencesAreNegotiated() throws Exception {
        assertThat(get("/quota", "zh;q=1,en;q=0.5").body())
                .isEqualTo("{\"code\":13001,\"msg\":\"配额已用完：5/5\",\"data\":null}");
        assertThat(get("/quota", "fr-FR,en-US;q=0.8,zh;q=0.5").body())
                .isEqualTo("{\"code\":13001,\"msg\":\"Quota exhausted: 5/5\",\"data\":null}");
        assertThat(get("/quota", "zh-CN,en;q=0.5").body())
                .isEqualTo("{\"code\":13001,\"msg\":\"配额已用完：5/5\",\"data\":null}");
        assertThat(get("/quota", "en;q=0,zh;q=0.8").body())
                .isEqualTo("{\"code\":13001,\"msg\":\"配额已用完：5/5\",\"data\":null}");
    }

    @Test
    void frameworkErrorsAreLocalizedFromTheBuiltInBundle() throws Exception {
        assertThat(get("/forbidden", "en").body()).isEqualTo("{\"code\":403,\"msg\":\"Access denied\",\"data\":null}");
        assertThat(get("/forbidden", "fr").body()).contains("没有访问权限");
    }

    @Test
    void frameworkAndApplicationTextsShareTheInjectedMessageSourceWithExplicitLocales() {
        assertThat(messageSource.getMessage("common.error.forbidden", null, Locale.ENGLISH)).isEqualTo("Access denied");
        assertThat(messageSource.getMessage("common.error.forbidden", null, Locale.CHINESE)).isEqualTo("没有访问权限");
        assertThat(messageSource.getMessage("sample.error.quota-exceeded", new Object[] {5, 5}, Locale.ENGLISH))
                .isEqualTo("Quota exhausted: 5/5");
    }

    @Test
    void missingBusinessTranslationsUseTheDiagnosticFallbackWithoutChangingTheErrorIdentity() throws Exception {
        var chinese = get("/untranslated", "zh");
        var english = get("/untranslated", "en");
        String expected = "{\"code\":13002,\"msg\":\"Quota service unavailable: 7\",\"data\":null}";

        assertThat(chinese.statusCode()).isEqualTo(503);
        assertThat(chinese.body()).isEqualTo(expected);
        assertThat(english.body()).isEqualTo(expected);
    }

    @Test
    void applicationBundlesCanOverrideFrameworkTextWithoutChangingCodes() throws Exception {
        var response = get("/conflict", "en");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).isEqualTo("{\"code\":409,\"msg\":\"Sample conflict override\",\"data\":null}");
    }

    @Test
    void unknownPathsAndUnexpectedFailuresUseTheSameEnvelopeWithoutLeakingDetails() throws Exception {
        var missing = get("/nowhere", "en");
        var broken = get("/boom", "en");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo("{\"code\":404,\"msg\":\"Resource not found\",\"data\":null}");
        assertThat(broken.statusCode()).isEqualTo(500);
        assertThat(broken.body()).isEqualTo("{\"code\":500,\"msg\":\"Internal server error\",\"data\":null}");
    }

    @Test
    void servletErrorDispatchesUseTheLocalizedEnvelopeInsteadOfBootErrorPages() throws Exception {
        var chinese = get("/servlet-error", "zh");
        var english = client.send(HttpRequest.newBuilder(uri("/servlet-error"))
                .header("Accept-Language", "en").header("Accept", "text/html").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(chinese.statusCode()).isEqualTo(503);
        assertThat(chinese.body()).isEqualTo("{\"code\":503,\"msg\":\"服务暂不可用\",\"data\":null}");
        assertThat(english.statusCode()).isEqualTo(503);
        assertThat(english.body()).isEqualTo("{\"code\":503,\"msg\":\"Service unavailable\",\"data\":null}");
        assertThat(english.headers().firstValue("X-Trace-Id")).isPresent();
    }

    @Test
    void mediaTypeFailuresAreLocalizedLikeOtherFrameworkErrors() throws Exception {
        var unsupported = client.send(HttpRequest.newBuilder(uri("/rename"))
                .header("Accept-Language", "zh").header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString("<private-input/>")).build(), HttpResponse.BodyHandlers.ofString());
        var unacceptable = client.send(HttpRequest.newBuilder(uri("/sample"))
                .header("Accept-Language", "zh").header("Accept", "application/xml").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(unsupported.statusCode()).isEqualTo(415);
        assertThat(unsupported.body()).isEqualTo("{\"code\":415,\"msg\":\"不支持的请求内容类型\",\"data\":null}");
        assertThat(unacceptable.statusCode()).isEqualTo(406);
        assertThat(unacceptable.body()).isEqualTo("{\"code\":406,\"msg\":\"不支持的响应内容类型\",\"data\":null}");
    }

    @Test
    void validationFailuresReportTheConstraintMessages() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/rename"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"code\":400,\"msg\":\"name is required\",\"data\":null}");
    }

    @Test
    void validationKeysAndBindingFailuresUseTheSameLanguageAsBusinessErrors() throws Exception {
        var chinese = client.send(HttpRequest.newBuilder(uri("/localized-rename"))
                .header("Accept-Language", "zh")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build(), HttpResponse.BodyHandlers.ofString());
        var english = client.send(HttpRequest.newBuilder(uri("/localized-rename"))
                .header("Accept-Language", "en")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(chinese.statusCode()).isEqualTo(400);
        assertThat(chinese.body()).isEqualTo("{\"code\":400,\"msg\":\"请输入名称\",\"data\":null}");
        assertThat(english.body()).isEqualTo("{\"code\":400,\"msg\":\"Name is required\",\"data\":null}");
        assertThat(get("/quantity?value=0", "zh").body())
                .isEqualTo("{\"code\":400,\"msg\":\"数量不能小于 1\",\"data\":null}");
        assertThat(get("/quantity?value=0", "en").body())
                .isEqualTo("{\"code\":400,\"msg\":\"Quantity must be at least 1\",\"data\":null}");
        assertThat(get("/quantity?value=private-input", "zh").body())
                .isEqualTo("{\"code\":400,\"msg\":\"请求参数错误\",\"data\":null}");
    }

    @Test
    void missingValidationTranslationsAndBindingDetailsNeverReachTheClient() throws Exception {
        var missing = client.send(HttpRequest.newBuilder(uri("/missing-validation"))
                .header("Accept-Language", "en").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\"}")).build(), HttpResponse.BodyHandlers.ofString());
        var binding = get("/form?quantity=private-input", "zh");

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).isEqualTo("{\"code\":400,\"msg\":\"Bad request\",\"data\":null}");
        assertThat(binding.statusCode()).isEqualTo(400);
        assertThat(binding.body()).isEqualTo("{\"code\":400,\"msg\":\"请求参数错误\",\"data\":null}");
        assertThat(get("/invalid-result", "en").body())
                .isEqualTo("{\"code\":500,\"msg\":\"Internal server error\",\"data\":null}");
    }

    @Test
    void responsesCarryATraceIdAndSecurityHeadersAndRejectMalformedUpstreamIds() throws Exception {
        var generated = get("/sample", "zh");
        var reused = client.send(HttpRequest.newBuilder(uri("/sample")).header("X-Trace-Id", "upstream-trace-0001").build(),
                HttpResponse.BodyHandlers.ofString());
        var forged = client.send(HttpRequest.newBuilder(uri("/sample")).header("X-Trace-Id", "bad id\twith spaces").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(generated.headers().firstValue("X-Trace-Id")).hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{32}"));
        assertThat(reused.headers().firstValue("X-Trace-Id")).contains("upstream-trace-0001");
        assertThat(forged.headers().firstValue("X-Trace-Id")).hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{32}"));
        assertThat(generated.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(generated.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(generated.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    private HttpResponse<String> get(String path, String language) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Accept-Language", language).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    enum SampleError implements ErrorCode {
        QUOTA_EXCEEDED;

        @Override
        public int code() {
            return 13001;
        }

        @Override
        public String message() {
            return "Quota exhausted: {0}/{1}";
        }

        @Override
        public String i18nKey() {
            return "sample.error.quota-exceeded";
        }

        @Override
        public int httpStatus() {
            return 409;
        }
    }

    enum UntranslatedError implements ErrorCode {
        UNAVAILABLE;

        @Override
        public int code() {
            return 13002;
        }

        @Override
        public String message() {
            return "Quota service unavailable: {0}";
        }

        @Override
        public String i18nKey() {
            return "sample.error.untranslated";
        }

        @Override
        public int httpStatus() {
            return 503;
        }
    }

    record Sample(UUID id, UUID parentId, Long boxedCount, long count, Integer boxedTotal, int total,
                  Long optionalCount, Instant createdAt) {}

    record Rename(@NotBlank(message = "name is required") String name) {}

    record LocalizedRename(@NotBlank(message = "{sample.validation.name-required}") String name) {}

    record MissingValidation(@NotBlank(message = "{sample.validation.missing}") String name) {}

    record Quantity(int quantity) {}

    @SpringBootApplication
    @RestController
    static class TestApplication {
        @GetMapping("/sample")
        R<Sample> sample() {
            return R.ok(new Sample(UUID.fromString("019aa220-6c88-7000-8000-000000000001"), null,
                    42L, 42, 7, 7, null, Instant.parse("2026-11-18T03:04:05Z")));
        }

        @GetMapping("/quota")
        R<Void> quota() {
            throw new ApiException(SampleError.QUOTA_EXCEEDED, 5, 5);
        }

        @GetMapping("/forbidden")
        R<Void> forbidden() {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }

        @GetMapping("/conflict")
        R<Void> conflict() {
            throw new ApiException(CommonErrorCode.CONFLICT);
        }

        @GetMapping("/untranslated")
        R<Void> untranslated() {
            throw new ApiException(UntranslatedError.UNAVAILABLE, 7);
        }

        @GetMapping("/boom")
        R<Void> boom() {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db");
        }

        @GetMapping("/servlet-error")
        void servletError(HttpServletResponse response) throws java.io.IOException {
            response.sendError(503, "private database detail");
        }

        @PostMapping("/rename")
        R<Void> rename(@Valid @RequestBody Rename rename) {
            return R.ok();
        }

        @PostMapping("/localized-rename")
        R<Void> localizedRename(@Valid @RequestBody LocalizedRename rename) {
            return R.ok();
        }

        @GetMapping("/quantity")
        R<Void> quantity(@RequestParam @Min(value = 1, message = "{sample.validation.quantity-min}") int value) {
            return R.ok();
        }

        @PostMapping("/missing-validation")
        R<Void> missingValidation(@Valid @RequestBody MissingValidation rename) {
            return R.ok();
        }

        @GetMapping("/form")
        R<Void> form(@Valid @ModelAttribute Quantity quantity) {
            return R.ok();
        }

        @GetMapping("/invalid-result")
        @Min(value = 1, message = "{sample.validation.quantity-min}")
        int invalidResult() {
            return 0;
        }
    }
}
