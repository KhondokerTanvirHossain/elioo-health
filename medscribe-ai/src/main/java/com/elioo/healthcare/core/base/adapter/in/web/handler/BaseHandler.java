package com.elioo.healthcare.core.base.adapter.in.web.handler;

import com.elioo.healthcare.core.base.application.port.dto.ApiResponse;
import com.elioo.healthcare.core.base.application.port.dto.DataList;
import com.elioo.healthcare.core.base.application.port.in.BaseUseCase;
import com.elioo.healthcare.core.base.domain.BaseDomain;
import com.elioo.healthcare.core.util.exception.AppException;
import com.elioo.healthcare.core.util.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public abstract class BaseHandler<T extends BaseDomain> {

    protected final BaseUseCase<T> useCase;
    private final Class<T> domainClass;

    public Mono<ServerResponse> getById(ServerRequest request) {
        String id = request.pathVariable("id");
        return useCase.getById(id)
                .flatMap(data -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ApiResponse.success("Resource fetched successfully", 200, data)))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return useCase.getAllDataAsList()
                .collectList()
                .flatMap(data -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(data))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    public Mono<ServerResponse> page(ServerRequest request) {
        int page = request.queryParam("page").map(Integer::parseInt).orElse(0);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(10);
        List<String> sortBy = request.queryParams().getOrDefault("sortBy", List.of("id"));
        List<String> sortDirection = request.queryParams().getOrDefault("sortDirection", List.of("asc"));

        return useCase.getAllDataWithPaginationAndSorting(page, size, sortBy, sortDirection)
                .flatMap(pageResponse -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(pageResponse))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    public Mono<ServerResponse> filter(ServerRequest request) {
        int page = request.queryParam("page").map(Integer::parseInt).orElse(0);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(10);
        List<String> sortBy = request.queryParams().getOrDefault("sortBy", List.of("id"));
        List<String> sortDirection = request.queryParams().getOrDefault("sortDirection", List.of("asc"));
        Map<String, String> filters = extractSingleValueFilters(request);

        return useCase.getAllDataWithPaginationAndSortingAndFiltering(page, size, sortBy, sortDirection, filters)
                .flatMap(pageResponse -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(pageResponse))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    public Mono<ServerResponse> listFilter(ServerRequest request) {
        int page = request.queryParam("page").map(Integer::parseInt).orElse(0);
        int size = request.queryParam("size").map(Integer::parseInt).orElse(10);
        List<String> sortBy = request.queryParams().getOrDefault("sortBy", List.of("id"));
        List<String> sortDirection = request.queryParams().getOrDefault("sortDirection", List.of("asc"));
        Map<String, List<String>> filters = extractListValueFilters(request);

        return useCase.getAllDataWithPaginationAndSortingAndListFiltering(page, size, sortBy, sortDirection, filters)
                .flatMap(pageResponse -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(pageResponse))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    private Map<String, String> extractSingleValueFilters(ServerRequest request) {
        Map<String, String> filters = new HashMap<>();
        request.queryParams().forEach((key, values) -> {
            if (!key.equals("page") && !key.equals("size") && !key.equals("sortBy") && !key.equals("sortDirection")) {
                filters.put(key, values.get(0));
            }
        });
        return filters;
    }

    private Map<String, List<String>> extractListValueFilters(ServerRequest request) {
        Map<String, List<String>> filters = new HashMap<>();
        request.queryParams().forEach((key, values) -> {
            if (!key.equals("page") && !key.equals("size") && !key.equals("sortBy") && !key.equals("sortDirection")) {
                if (values.size() == 1 && values.get(0).contains(",")) {
                    filters.put(key, Arrays.asList(values.get(0).split(",")));
                } else {
                    filters.put(key, values);
                }
            }
        });
        return filters;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(domainClass)
                .flatMap(useCase::create)
                .flatMap(created -> ServerResponse.status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ApiResponse.success("Resource created successfully", 201, created)))
                .onErrorResume(e -> e instanceof AppException ?
                        handleAppException((AppException) e) : handleUnexpectedException(e, request))
                ;
    }

    public Mono<ServerResponse> createList(ServerRequest request) {
        java.lang.reflect.Type dataListType = new java.lang.reflect.ParameterizedType() {
            @Override
            public java.lang.reflect.Type[] getActualTypeArguments() {
                return new java.lang.reflect.Type[]{domainClass};
            }

            @Override
            public java.lang.reflect.Type getRawType() {
                return DataList.class;
            }

            @Override
            public java.lang.reflect.Type getOwnerType() {
                return null;
            }
        };

        ParameterizedTypeReference<DataList<T>> typeRef = ParameterizedTypeReference.forType(dataListType);

        return request.bodyToMono(typeRef)
                .doOnNext(list -> log.debug("Received list for creation: {}", list))
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Request body cannot be empty")))
                .flatMapMany(useCase::createList)
                .collectList()
                .flatMap(created -> ServerResponse.status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ApiResponse.success("List created successfully", 201, created)))
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request))
                ;
    }

    public Mono<ServerResponse> update(ServerRequest request) {
        String id = request.pathVariable("id");
        return request.bodyToMono(domainClass)
                .flatMap(obj -> useCase.update(id, obj))
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(ApiResponse.success("Resource updated successfully", 200, updated)))
                .onErrorResume(e -> e instanceof AppException ?
                        handleAppException((AppException) e) : handleUnexpectedException(e, request))
                ;
    }

    public Mono<ServerResponse> updateByFilter(ServerRequest request) {
        ParameterizedTypeReference<DataList<Map<String, Object>>> typeRef = new ParameterizedTypeReference<>() {};
        return request.bodyToMono(typeRef)
                .doOnNext(dataList -> log.debug("Received update filter request: {}", dataList))
                .flatMap(dataList -> {
                    if (dataList.getItems() == null || dataList.getItems().isEmpty()) {
                        return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Request body cannot be empty"));
                    }
                    Map<String, Object> requestData = dataList.getItems().get(0);
                    Map<String, Object> filterCriteria = (Map<String, Object>) requestData.get("filterCriteria");
                    Map<String, Object> updateFields = (Map<String, Object>) requestData.get("updateFields");
                    if (filterCriteria == null || filterCriteria.isEmpty()) {
                        return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Filter criteria cannot be empty"));
                    }
                    if (updateFields == null || updateFields.isEmpty()) {
                        return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Update fields cannot be empty"));
                    }
                    return useCase.updateByFilter(filterCriteria, updateFields)
                            .collectList()
                            .flatMap(updated -> ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(ApiResponse.success("Resources updated successfully", 200, updated)));
                })
                .onErrorResume(AppException.class, this::handleAppException)
                .onErrorResume(e -> handleUnexpectedException(e, request));
    }

    protected Mono<ServerResponse> handleAppException(AppException e) {
        log.error("Application error: {}", e.getMessage(), e);
        return ServerResponse.status(e.getErrorCode().getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    protected Mono<ServerResponse> handleUnexpectedException(Throwable e, ServerRequest request) {
        log.error("Unexpected error for request {}: {}", request.uri(), e.getMessage(), e);
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ApiResponse.error(ErrorCode.UNKNOWN_ERROR, "An unexpected error occurred"));
    }
}