package com.classschedule.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MasterDataRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Positive Integer capacity,
        @PositiveOrZero Integer studentCount,
        @Size(max = 64) String roomType) {
    public MasterDataRequest(String code, String name, Integer capacity, String roomType) {
        this(code, name, capacity, 0, roomType);
    }
}
