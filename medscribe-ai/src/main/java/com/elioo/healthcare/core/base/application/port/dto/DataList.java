package com.elioo.healthcare.core.base.application.port.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class DataList<T> {
    @NotEmpty(message = "Items list cannot be empty")
    private List<T> items;
}
