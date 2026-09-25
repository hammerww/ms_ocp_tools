package com.ocptools.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TcpCheckRequest(
        @NotBlank
        @Size(max = 15)
        String ip,

        @Min(1)
        @Max(65535)
        int port
) {
}
