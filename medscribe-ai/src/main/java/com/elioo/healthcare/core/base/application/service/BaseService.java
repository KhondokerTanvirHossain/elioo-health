package com.elioo.healthcare.core.base.application.service;

import com.elioo.healthcare.core.base.application.port.dto.DataList;
import com.elioo.healthcare.core.base.application.port.dto.PageResponse;
import com.elioo.healthcare.core.base.application.port.in.BaseUseCase;
import com.elioo.healthcare.core.base.application.port.out.persistence.BasePersistencePort;
import com.elioo.healthcare.core.base.domain.BaseDomain;
import com.elioo.healthcare.core.util.CommonFunctions;
import com.elioo.healthcare.core.util.exception.AppException;
import com.elioo.healthcare.core.util.exception.ErrorCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class BaseService<T extends BaseDomain> implements BaseUseCase<T> {

    private final BasePersistencePort<T> persistencePort;
    private final Validator validator;
    private final TransactionalOperator transactionalOperator;
    private final Class<T> domainClass;

    @Override
    public Mono<T> getById(String id) {
        if (id == null || id.isBlank()) {
            return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "ID is required"));
        }

        return persistencePort.getById(id)
                .doOnRequest(v -> log.info("Attempting to get resource with ID {}", id))
                .doOnSuccess(result -> log.info("Fetched with ID {} : {}", id, result))
                .doOnError(e -> log.error("Error fetching with ID {}: {}", id, e.getMessage(), e))
                .onErrorResume(e -> !(e instanceof AppException) ? Mono.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to fetch resource", e)) : Mono.error(e));
    }

    @Override
    public Flux<T> getAllDataAsList() {
        return persistencePort.getAllDataAsList();
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSorting(int page, int size, List<String> sortBy, List<String> sortDirection) {
        return persistencePort.getAllDataWithPaginationAndSorting(page, size, sortBy, sortDirection)
                .map(pagedData -> new PageResponse<>("Data retrieved successfully",
                        pagedData.getTotalElements(),
                        pagedData.getTotalPages(),
                        200,
                        pagedData.getPage(),
                        pagedData.getSize(),
                        pagedData.getData()));
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, String> filters) {
        return persistencePort.getAllDataWithPaginationAndSortingAndFiltering(page, size, sortBy, sortDirection, filters)
                .map(pagedData -> new PageResponse<>("Filtered data retrieved successfully",
                        pagedData.getTotalElements(),
                        pagedData.getTotalPages(),
                        200,
                        pagedData.getPage(),
                        pagedData.getSize(),
                        pagedData.getData()));
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndListFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, List<String>> filters) {
        return persistencePort.getAllDataWithPaginationAndSortingAndListFiltering(page, size, sortBy, sortDirection, filters)
                .map(pagedData -> new PageResponse<>("Filtered data retrieved successfully",
                        pagedData.getTotalElements(),
                        pagedData.getTotalPages(),
                        200,
                        pagedData.getPage(),
                        pagedData.getSize(),
                        pagedData.getData()));
    }

    @Override
    public Mono<T> create(T domainObject) {
        if (domainObject == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Domain object cannot be null");
        }
        T updatedDomainObject = updatedDomainObject(domainObject);
        domainObject.setId(null);
        return Mono.just(updatedDomainObject)
                .flatMap(this::validate)
                .flatMap(this::businessValidation)
                .flatMap(persistencePort::create)
                .as(transactionalOperator::transactional)
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to create domain object")))
                .flatMap(this::afterCreate)
                .onErrorResume(e -> !(e instanceof AppException) ? Mono.error(new AppException(ErrorCode.DATABASE_ERROR, e.getMessage())) : Mono.error(e))
                .doOnRequest(v -> log.info("Attempting to Create domain object {}", domainObject))
                .doOnSuccess(v -> log.info("Domain object created successfully: {}", domainObject))
                .doOnError(e -> log.error("Error creating domain object: {}", e.getMessage(), e));
    }

    @Override
    public Flux<T> createList(DataList<T> domainObjects) {
        if (domainObjects == null || domainObjects.getItems().isEmpty()) {
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Domain object list cannot be null or empty"));
        }

        return Flux.fromIterable(domainObjects.getItems())
                .doOnNext(obj -> {
                    if (obj == null) {
                        throw new AppException(ErrorCode.INVALID_REQUEST, "Domain object in list cannot be null");
                    }
                    obj.setId(null);
                })
                .map(this::updatedDomainObject)
                .concatMap(obj -> validate(obj)
                        .flatMap(this::businessValidation))
                .collectList()
                .flatMapMany(persistencePort::createList)
                .collectList()
                .flatMapMany(this::afterCreateList)
                .as(transactionalOperator::transactional)
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to create domain object")))
                .onErrorResume(e -> !(e instanceof AppException) ? Mono.error(new AppException(ErrorCode.DATABASE_ERROR, e.getMessage(), e)) : Mono.error(e))
                .doOnNext(created -> log.info("Domain object created successfully: {}", created))
                .doOnError(e -> log.error("Error creating domain object list: {}", e.getMessage(), e))
                .doOnRequest(v -> log.info("Attempting to create {} domain objects", domainObjects.getItems().size()));
    }

    private Mono<T> validate(T domainObject) {
        var violations = validator.validate(domainObject);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Validation failed: " + errorMessage));
        }
        return Mono.just(domainObject);
    }

    public Mono<T> businessValidation(T domainObject) {
        return Mono.just(domainObject);
    }

    public T updatedDomainObject(T domainObject) {
        return domainObject;
    }

    protected Mono<T> afterCreate(T createdObject) {
        return Mono.just(createdObject);
    }

    protected Flux<T> afterCreateList(List<T> createdObject) {
        return Flux.fromIterable(createdObject);
    }

    @Override
    public Mono<T> update(String id, T domainObject) {
        if (id == null || id.isBlank()) {
            return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Notification ID is required"));
        }
        if (domainObject == null) {
            return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Domain object cannot be null"));
        }

        Map<String, Object> fields = getNonNullFields(domainObject);
        if (fields.isEmpty()) {
            return persistencePort.getById(id)
                    .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")))
                    .onErrorResume(e -> !(e instanceof AppException) ? Mono.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to update resource", e)) : Mono.error(e))
                    .doOnRequest(v -> log.info("Attempting to fetch resource with ID {}: {}", id, fields))
                    .doOnError(e -> log.error("Error updating resource with ID {}: {}", id, e.getMessage(), e))
                    .doOnSuccess(found -> log.info("No fields to update, returning existing resource: {}", found));
        }

        return validateFields(domainObject, fields) // Validate only non-null fields
                .flatMap(obj -> persistencePort.getById(id))
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")))
                .flatMap(existing -> {
                    T updatedObject = mergeFields(existing, fields); // Merge fields into existing object
                    return persistencePort.update(id, updatedObject) // Update with merged object
                            .as(transactionalOperator::transactional)
                            .doOnSuccess(updated -> log.info("Resource updated successfully: {}", updated));
                })
                .onErrorResume(e -> !(e instanceof AppException) ? Mono.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to update resource", e)) : Mono.error(e))
                .doOnRequest(v -> log.info("Attempting to update resource with ID {}: {}", id, fields))
                .doOnError(e -> log.error("Error updating resource with ID {}: {}", id, e.getMessage(), e));
    }

    @Override
    public Flux<T> updateByFilter(Map<String, Object> filterCriteria, Map<String, Object> updateFields) {
        if (filterCriteria == null || filterCriteria.isEmpty()) {
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Filter criteria cannot be empty"));
        }
        if (updateFields == null || updateFields.isEmpty()) {
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Update fields cannot be empty"));
        }

        Map<String, Object> convertedFields = convertDateTimeFields(domainClass, updateFields);

        return validateFields(convertedFields)
                .thenMany(persistencePort.updateByFilter(filterCriteria, convertedFields))
                .switchIfEmpty(Flux.error(new AppException(ErrorCode.NOT_FOUND, "No resources found matching filter criteria")))
                .onErrorResume(e -> !(e instanceof AppException) ? Flux.error(new AppException(ErrorCode.DATABASE_ERROR, "Failed to update resources by filter", e)) : Flux.error(e))
                .doOnRequest(v -> log.info("Attempting to update resources with filter: {}, fields: {}", filterCriteria, updateFields))
                .doOnError(e -> log.error("Error updating resources with filter: {}", e.getMessage(), e));
    }

    private Map<String, Object> getNonNullFields(T domainObject) {
        if (domainObject == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Domain object cannot be null");
        }
        Map<String, Object> fields = new HashMap<>();
        try {
            for (Field field : domainObject.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(domainObject);
                if (value != null && !field.getName().equals("id")) {
                    String columnName = CommonFunctions.camelToSnake(field.getName());
                    fields.put(columnName, value);
                }
            }
        } catch (IllegalAccessException e) {
            throw new AppException(ErrorCode.UNKNOWN_ERROR, "Failed to process update fields", e);
        }
        return fields;
    }

    private Mono<T> validateFields(T domainObject, Map<String, Object> fields) {
        try {
            // Create a new instance of T to validate only provided fields
            @SuppressWarnings("unchecked")
            T tempObject = (T) domainObject.getClass().getDeclaredConstructor().newInstance();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String columnName = entry.getKey();
                String fieldName = CommonFunctions.snakeToCamel(columnName);
                Field field = domainObject.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(tempObject, entry.getValue());
            }
            var violations = validator.validate(tempObject);
            if (!violations.isEmpty()) {
                String errorMessage = violations.stream()
                        .filter(v -> fields.containsKey(CommonFunctions.camelToSnake(v.getPropertyPath().toString())))
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                if (!errorMessage.isEmpty()) {
                    return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Validation failed: " + errorMessage));
                }
            }
            return Mono.just(domainObject != null ? domainObject : tempObject);
        } catch (Exception e) {
            return Mono.error(new AppException(ErrorCode.UNKNOWN_ERROR, "Failed to validate fields", e));
        }
    }

    protected Mono<Void> validateFields(Map<String, Object> fields) {
        try {
            T tempObject = domainClass.getDeclaredConstructor().newInstance();
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                try {
                    Field field = domainClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = entry.getValue();
                    // Generic: If the field is LocalDateTime and value is String, convert
                    if (field.getType() == LocalDateTime.class && value instanceof String) {
                        try {
                            value = LocalDateTime.parse((String) value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        } catch (DateTimeParseException e) {
                            return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Invalid date format for field: " + fieldName, e));
                        }
                    }
                    field.set(tempObject, value);
                } catch (NoSuchFieldException e) {
                    return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Invalid field: " + fieldName, e));
                }
            }
            var violations = validator.validate(tempObject);
            if (!violations.isEmpty()) {
                String errorMessage = violations.stream()
                        .filter(v -> fields.containsKey(v.getPropertyPath().toString()))
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                if (!errorMessage.isEmpty()) {
                    return Mono.error(new AppException(ErrorCode.INVALID_REQUEST, "Validation failed: " + errorMessage));
                }
            }
            return Mono.empty();
        } catch (Exception e) {
            return Mono.error(new AppException(ErrorCode.UNKNOWN_ERROR, "Failed to validate fields", e));
        }
    }

    private T mergeFields(T existing, Map<String, Object> fields) {
        try {
            T updatedObject = domainClass.getDeclaredConstructor().newInstance();
            for (Field field : existing.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                field.set(updatedObject, field.get(existing));
            }
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String columnName = entry.getKey();
                String fieldName = CommonFunctions.snakeToCamel(columnName);
                try {
                    Field field = domainClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = entry.getValue();
                    if (field.getType() == LocalDateTime.class && value instanceof String) {
                        try {
                            value = LocalDateTime.parse((String) value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        } catch (DateTimeParseException e) {
                            throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid date format for field: " + fieldName, e);
                        }
                    }
                    field.set(updatedObject, value);
                } catch (NoSuchFieldException e) {
                    throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid field: " + fieldName, e);
                }
            }
            return updatedObject;
        } catch (Exception e) {
            throw new AppException(ErrorCode.UNKNOWN_ERROR, "Failed to merge fields", e);
        }
    }

    private Map<String, Object> convertDateTimeFields(Class<?> entityClass, Map<String, Object> fields) {
        Map<String, Object> converted = new HashMap<>(fields);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            try {
                Field entityField = entityClass.getDeclaredField(fieldName);
                if (entityField.getType().equals(java.time.LocalDateTime.class) && value instanceof String) {
                    converted.put(fieldName, LocalDateTime.parse((String) value));
                }
            } catch (NoSuchFieldException ignored) {
                // Not a field in entity, skip
            }
        }
        return converted;
    }
}