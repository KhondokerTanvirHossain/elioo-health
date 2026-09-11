package com.elioo.healthcare.core.base.application.port.in;

import com.elioo.healthcare.core.base.application.port.dto.DataList;
import com.elioo.healthcare.core.base.application.port.dto.PageResponse;
import com.elioo.healthcare.core.base.domain.BaseDomain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface BaseUseCase<T extends BaseDomain> {
    Mono<T> getById(String id);
    Flux<T> getAllDataAsList();
    Mono<PageResponse<T>> getAllDataWithPaginationAndSorting(int page, int size, List<String> sortBy, List<String> sortDirection);
    Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, String> filters);
    Mono<PageResponse<T>> getAllDataWithPaginationAndSortingAndListFiltering(int page, int size, List<String> sortBy, List<String> sortDirection, Map<String, List<String>> filters);
    Mono<T> create(T domainObject);
    Mono<T> update(String id, T domainObject);
    Flux<T> createList(DataList<T> domainObjects);
    Flux<T> updateByFilter(Map<String, Object> filterCriteria, Map<String, Object> updateFields);
}
