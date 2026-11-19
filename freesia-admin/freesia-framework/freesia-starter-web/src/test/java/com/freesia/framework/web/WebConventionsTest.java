package com.freesia.framework.web;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.error.ErrorCode;
import com.freesia.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = WebConventionsTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebConventionsTest {
    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void boxedLongsBecomeStringsWhilePrimitiveCountsStayNumericAndInstantsAreUtcIso() throws Exception {
        var response = get("/sample", "zh");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "{\"code\":200,\"msg\":\"OK\",\"data\":{\"id\":\"9007199254740993\",\"parentId\":null,\"count\":42,\"createdAt\":\"2026-11-18T03:04:05Z\"}}");
    }

    @Test
    void businessErrorsKeepTheirCodeUseTheirHttpStatusAndFollowTheRequestLanguage() throws Exception {
        var chinese = get("/quota", "zh");
        var english = get("/quota", "en");

        assertThat(chinese.statusCode()).isEqualTo(409);
        assertThat(chinese.body()).isEqualTo("{\"code\":13001,\"msg\":\"配额已用完：5/5\",\"data\":null}");
        assertThat(english.body()).isEqualTo("{\"code\":13001,\"msg\":\"Quota exhausted: 5/5\",\"data\":null}");
    }

    @Test
    void frameworkErrorsAreLocalizedFromTheBuiltInBundle() throws Exception {
        assertThat(get("/forbidden", "en").body()).isEqualTo("{\"code\":403,\"msg\":\"Access denied\",\"data\":null}");
        assertThat(get("/forbidden", "fr").body()).contains("没有访问权限");
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
    void validationFailuresReportTheConstraintMessages() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/rename"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\" \"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"code\":400,\"msg\":\"name is required\",\"data\":null}");
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
            return "配额已用完：{0}/{1}";
        }

        @Override
        public int httpStatus() {
            return 409;
        }
    }

    record Sample(Long id, Long parentId, long count, Instant createdAt) {}

    record Rename(@NotBlank(message = "name is required") String name) {}

    @SpringBootApplication
    @RestController
    static class TestApplication {
        @GetMapping("/sample")
        R<Sample> sample() {
            return R.ok(new Sample(9_007_199_254_740_993L, null, 42, Instant.parse("2026-11-18T03:04:05Z")));
        }

        @GetMapping("/quota")
        R<Void> quota() {
            throw new ApiException(SampleError.QUOTA_EXCEEDED, 5, 5);
        }

        @GetMapping("/forbidden")
        R<Void> forbidden() {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }

        @GetMapping("/boom")
        R<Void> boom() {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db");
        }

        @PostMapping("/rename")
        R<Void> rename(@Valid @RequestBody Rename rename) {
            return R.ok();
        }
    }
}
