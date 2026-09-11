package com.elioo.healthcare.core.util.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class WebClientExceptionHandlerUtil extends RuntimeException {
    public HttpStatus code;
    public String message;
    public Map<String, String> body;
}
