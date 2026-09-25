package com.ocptools.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ToolRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9]+", message = "AccessID solo admite letras y números")
        String accessId,

        @NotBlank
        @Size(max = 16)
        String environment
) {
}

