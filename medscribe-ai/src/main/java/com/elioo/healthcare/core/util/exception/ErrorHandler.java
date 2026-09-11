package com.elioo.healthcare.core.util.exception;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@UtilityClass
@Slf4j
public class ErrorHandler {

    public Mono<ServerResponse> buildErrorResponseForBusiness(ExceptionHandlerUtil ex, ServerRequest request) {
        return ServerResponse.status(ex.getCode()).bodyValue(new ErrorBody(ex, request.path()));
    }

    public Mono<ServerResponse> buildErrorResponseForUncaught(Throwable ex, ServerRequest request) {
        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyValue(new ErrorBody(HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "Something Went Wrong!", request.path()))
//                .bodyValue(new ErrorBody(HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), ex.getMessage(), request.path()))
                .doOnError(throwable -> log.error("Error occurred while processing request : {}", ex.getMessage()));
    }

}
