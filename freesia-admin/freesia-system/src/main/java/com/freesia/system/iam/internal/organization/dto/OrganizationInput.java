package com.freesia.system.iam.internal.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OrganizationInput(UUID parentId,
                                @NotBlank(message = "{iam.validation.organization-code}")
                                @Size(max = 80, message = "{iam.validation.organization-code}") String code,
                                @NotBlank(message = "{iam.validation.organization-name}")
                                @Size(max = 100, message = "{iam.validation.organization-name}") String name,
                                @Size(max = 500, message = "{iam.validation.remark-length}") String remark,
                                int sort) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name,Object value) {
        throw new IllegalArgumentException("Unsupported organization field");
    }
}
