package com.ocptools.ocpmap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TestingMarkRemovalRequest(
        @NotBlank @Size(max = 120) String responsible,
        @Size(max = 500) String note
) {
}
