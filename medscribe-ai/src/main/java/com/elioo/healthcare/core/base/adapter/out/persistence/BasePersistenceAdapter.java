package com.elioo.healthcare.core.base.adapter.out.persistence;

import com.elioo.healthcare.core.base.adapter.out.persistence.criteria.DynamicCriteriaBuilder;
import com.elioo.healthcare.core.base.adapter.out.persistence.entity.BaseEntity;
import com.elioo.healthcare.core.base.adapter.out.persistence.repository.GenericRepository;
import com.elioo.healthcare.core.base.application.port.dto.PageResponse;
import com.elioo.healthcare.core.base.application.port.out.persistence.BasePersistencePort;
import com.elioo.healthcare.core.base.domain.BaseDomain;
import com.elioo.healthcare.core.util.CommonFunctions;
import com.elioo.healthcare.core.util.exception.AppException;
import com.elioo.healthcare.core.util.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public abstract class BasePersistenceAdapter<T extends BaseDomain, E extends BaseEntity> implements BasePersistencePort<T> {

    protected final GenericRepository<E, String> repository;
    protected final ModelMapper modelMapper;
    protected final Class<T> dtoClass;
    protected final Class<E> entityClass;

    protected BasePersistenceAdapter(GenericRepository<E, String> repository, ModelMapper modelMapper, Class<T> dtoClass, Class<E> entityClass) {
        this.repository = repository;
        this.modelMapper = modelMapper;
        this.dtoClass = dtoClass;
        this.entityClass = entityClass;
    }

    @Override
    public Flux<T> getAllDataAsList() {
        return repository.findAll()
                .map(this::converter)
                .onErrorMap(e -> new AppException(ErrorCode.DATABASE_ERROR, "Failed to fetch all data", e));
    }

    @Override
    public Mono<T> getById(String id) {
        return repository.findById(id)
                .map(this::converter)
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")))
                .onErrorMap(e -> e instanceof AppException ? e : new AppException(ErrorCode.DATABASE_ERROR, "Failed to fetch resource by ID", e))
                .doOnRequest(v -> log.info("Fetching resource with ID: {}", id))
                .doOnError(e -> log.error("Error fetching resource with ID {}: {}", id, e.getMessage(), e));
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSorting(int page, int size, List<String> sortBy, List<String> sortDirection) {
        if (page < 0 || size <= 0) {
            throw new AppException(ErrorCode.INVALID_PAGE_SIZE, "Page must be >= 0 and size must be > 0");
        }
        if (sortBy.isEmpty() || sortDirection.isEmpty() || sortBy.size() != sortDirection.size()) {
            throw new AppException(ErrorCode.INVALID_SORT, "Invalid sort parameters");
        }
        Sort sort = createSort(sortBy, sortDirection);
        Pageable pageable = PageRequest.of(page, size, sort);
        return fetchPage(Criteria.empty(), pageable);
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, String> filters) {
        if (page < 0 || size <= 0) {
            throw new AppException(ErrorCode.INVALID_PAGE_SIZE, "Page must be >= 0 and size must be > 0");
        }
        if (sortBy.isEmpty() || sortDirection.isEmpty() || sortBy.size() != sortDirection.size()) {
            throw new AppException(ErrorCode.INVALID_SORT, "Invalid sort parameters");
        }
        if (filters == null || filters.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_FILTER, "Filters cannot be empty");
        }
        Sort sort = createSort(sortBy, sortDirection);
        Pageable pageable = PageRequest.of(page, size, sort);
        Criteria criteria = DynamicCriteriaBuilder.buildSingleValueCriteria(entityClass, filters);
        return fetchPage(criteria, pageable);
    }

    @Override
    public Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndListFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, List<String>> filters) {
        if (page < 0 || size <= 0) {
            throw new AppException(ErrorCode.INVALID_PAGE_SIZE, "Page must be >= 0 and size must be > 0");
        }
        if (sortBy.isEmpty() || sortDirection.isEmpty() || sortBy.size() != sortDirection.size()) {
            throw new AppException(ErrorCode.INVALID_SORT, "Invalid sort parameters");
        }
        if (filters == null || filters.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_FILTER, "Filters cannot be empty");
        }
        Sort sort = createSort(sortBy, sortDirection);
        Pageable pageable = PageRequest.of(page, size, sort);
        Criteria criteria = DynamicCriteriaBuilder.buildListValueCriteria(entityClass, filters);
        return fetchPage(criteria, pageable);
    }

    private Sort createSort(List<String> sortBy, List<String> sortDirection) {
        try {
            return Sort.by(
                    sortBy.stream()
                            .map(field -> new Sort.Order(Sort.Direction.fromString(sortDirection.get(sortBy.indexOf(field))), field))
                            .collect(Collectors.toList())
            );
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_SORT, "Invalid sort direction", e);
        }
    }

    private Mono<PageResponse<T>> fetchPage(Criteria criteria, Pageable pageable) {
        Mono<List<T>> dataMono = repository.selectByCriteria(criteria, pageable)
                .map(this::converter)
                .collectList()
                .doOnNext(list -> log.info("Fetched page from database : {}", list.toString()));
        Mono<Long> countMono = repository.countByCriteria(criteria)
                .doOnNext(count -> log.info("Fetched count from database : {}", count.toString()));
        return Mono.zip(dataMono, countMono)
                .map(tuple -> {
                    List<T> data = tuple.getT1();
                    long totalElements = tuple.getT2();
                    int totalPages = (int) Math.ceil((double) totalElements / pageable.getPageSize());
                    if (totalPages == 0 && totalElements > 0) {
                        totalPages = 1;
                    }
                    return new PageResponse<>(
                            "Filtered data retrieved successfully",
                            totalElements,
                            totalPages,
                            200,
                            pageable.getPageNumber(),
                            pageable.getPageSize(),
                            data
                    );
                });
    }

    @Override
    public Mono<T> create(T domainObject) {
        E entity = modelMapper.map(domainObject, entityClass);
        return repository.save(entity)
                .map(this::converter)
                .onErrorMap(e -> e instanceof DuplicateKeyException ?
                        new AppException(ErrorCode.DUPLICATE_RESOURCE, "Resource with ID already exists", e) :
                        new AppException(ErrorCode.DATABASE_ERROR, "Failed to create resource", e))
                .doOnRequest(v -> log.info("Creating resource: {}", domainObject))
                .doOnError(e -> log.error(e.getMessage(), e))
                .doOnSuccess(dbEntityConverted -> log.info("Saving resource: {}", dbEntityConverted));
    }

    @Override
    public Flux<T> createList(List<T> domainObjects) {
        List<E> entityList = domainObjects.stream().map(domainObject -> modelMapper.map(domainObject, entityClass)).toList();
        return repository.saveAll(entityList)
                .map(this::converter)
                .onErrorMap(e -> e instanceof DuplicateKeyException ?
                        new AppException(ErrorCode.DUPLICATE_RESOURCE, "Resource with ID already exists", e) :
                        new AppException(ErrorCode.DATABASE_ERROR, "Failed to create resource", e))
                .doOnRequest(v -> log.info("Creating resource with size : {}", domainObjects.size()))
                .doOnError(e -> log.error(e.getMessage(), e))
                .doOnComplete(() -> log.info("resource saved"));
    }

    @Override
    public Mono<T> update(String id, T domainObject) {
        Map<String, Object> fields = getNonNullFields(domainObject);
        if (fields.isEmpty()) {
            return repository.findById(id)
                    .map(this::converter)
                    .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")));
        }
        return repository.update(id, fields)
                .map(this::converter)
                .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")))
                .onErrorMap(e -> {
                    if (e instanceof AppException) {
                        return e; // Preserve AppException (e.g., NOT_FOUND)
                    }
                    return new AppException(ErrorCode.DATABASE_ERROR, "Failed to update resource", e);
                })
                .doOnRequest(v -> log.info("Updating resource: {}", domainObject))
                .doOnError(e -> log.error("Error updating resource with ID {}: {}", id, e.getMessage(), e))
                .doOnSuccess(dbEntityConverted -> log.info("Updated resource: {}", dbEntityConverted));
    }

    public Flux<T> updateByFilter(Map<String, Object> filterCriteria, Map<String, Object> updateFields) {
        if (filterCriteria == null || filterCriteria.isEmpty()) {
            log.error("Filter criteria is null or empty");
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Filter criteria cannot be empty"));
        }
        if (updateFields == null || updateFields.isEmpty()) {
            log.error("Update fields is null or empty");
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Update fields cannot be empty"));
        }
        log.info("AAA | Filter criteria: {}", filterCriteria);

        return repository.updateByFilter(filterCriteria, updateFields)
                .map(this::converter)
                .switchIfEmpty(Flux.error(new AppException(ErrorCode.NOT_FOUND, "No resources found matching filter criteria")))
                .onErrorMap(e -> {
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException(ErrorCode.DATABASE_ERROR, "Failed to update resources by filter", e);
                })
                .doOnRequest(v -> log.info("Updating resources with filter: {}, fields: {}", filterCriteria, updateFields))
                .doOnError(e -> log.error("Error updating resources with filter: {}", e.getMessage(), e))
                .doOnNext(updated -> log.info("Updated resource: {}", updated));
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

    protected T converter(E entity) {
        try {
            return modelMapper.map(entity, dtoClass);
        } catch (Exception e) {
            log.error("Error converting entity to DTO: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.DATABASE_ERROR, "Failed to convert entity to DTO", e);
        }
    }


}