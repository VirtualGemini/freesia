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
            return "配额已用完：{0}/{1}";
        }
    }

    @Test
    void businessErrorsDefaultToBadRequestAndDeriveTheirTranslationKey() {
        var exception = new ApiException(SampleError.QUOTA_EXCEEDED, 5, 5);

        assertThat(exception.getMessage()).isEqualTo("配额已用完：5/5");
        assertThat(exception.errorCode().httpStatus()).isEqualTo(400);
        assertThat(exception.errorCode().i18nKey()).isEqualTo("SampleError.QUOTA_EXCEEDED");
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
}
