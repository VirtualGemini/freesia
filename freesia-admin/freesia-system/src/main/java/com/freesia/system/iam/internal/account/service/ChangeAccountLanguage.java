package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Component
public class ChangeAccountLanguage {
    private final AccountWrites writes;

    public ChangeAccountLanguage(AccountWrites writes) {
        this.writes = writes;
    }

    @Transactional
    public boolean change(UUID accountId, String language) {
        if (language == null || !Set.of("zh", "en").contains(language)) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        if (writes.changeLanguage(accountId, language) != 1) {
            throw new ApiException(IamErrorCode.SESSION_INVALID);
        }
        return true;
    }
}
