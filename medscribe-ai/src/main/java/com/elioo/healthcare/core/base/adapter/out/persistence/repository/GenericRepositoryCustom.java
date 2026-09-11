package com.elioo.healthcare.core.base.adapter.out.persistence.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.relational.core.query.Criteria;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface GenericRepositoryCustom<T> {
    Flux<T> selectByCriteria(Criteria criteria, Pageable pageable);
    Mono<Long> countByCriteria(Criteria criteria);
    Mono<T> update(String id, Map<String, Object> fields);
    Flux<T> updateByFilter(Map<String, Object> filterCriteria, Map<String, Object> updateFields);
}