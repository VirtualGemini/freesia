package com.freesia.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {
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
    }

    @Test
    void businessErrorsDefaultToBadRequestAndKeepTheirExplicitTranslationKey() {
        var exception = new ApiException(SampleError.QUOTA_EXCEEDED, 5, 5);

        assertThat(exception.getMessage()).isEqualTo("Quota exhausted: 5/5");
        assertThat(exception.errorCode().httpStatus()).isEqualTo(400);
        assertThat(exception.errorCode().i18nKey()).isEqualTo("sample.error.quota-exceeded");
    }

    @Test
    void textWithoutArgumentsIsNotTreatedAsAPattern() {
        assertThat(ApiException.format("can't parse {id}")).isEqualTo("can't parse {id}");
    }

    @Test
    void frameworkCodesEqualTheirHttpStatus() {
        assertThat(CommonErrorCode.values()).allSatisfy(code -> assertThat(code.httpStatus()).isEqualTo(code.code()));
        assertThat(CommonErrorCode.ofStatus(404)).contains(CommonErrorCode.NOT_FOUND);
        assertThat(CommonErrorCode.ofStatus(418)).isEmpty();
    }

    @Test
    void frameworkErrorsHaveExplicitStableTranslationKeysAndDiagnosticFallbacks() {
        assertThat(CommonErrorCode.FORBIDDEN.i18nKey()).isEqualTo("common.error.forbidden");
        assertThat(new ApiException(CommonErrorCode.FORBIDDEN).getMessage()).isEqualTo("Access denied");
    }
}
