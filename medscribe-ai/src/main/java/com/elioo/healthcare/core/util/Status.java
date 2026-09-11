package com.elioo.healthcare.core.util;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum Status {
    SENT("SENT"),

    ;

    private final String value;

    Status(String value) {
        this.value = value;
    }
}