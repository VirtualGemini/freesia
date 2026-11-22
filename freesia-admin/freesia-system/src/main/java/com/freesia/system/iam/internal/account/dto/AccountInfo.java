package com.freesia.system.iam.internal.account.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record AccountInfo(UUID accountId, String userName, String email, String phone, String avatar,
                          String language, String timeZone, List<String> roles, Set<String> buttons) {}
