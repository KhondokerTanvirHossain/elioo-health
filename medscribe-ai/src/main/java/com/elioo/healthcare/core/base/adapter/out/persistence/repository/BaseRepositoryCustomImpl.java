package com.elioo.healthcare.core.base.adapter.out.persistence.repository;

import com.elioo.healthcare.core.util.CommonFunctions;
import com.elioo.healthcare.core.util.exception.AppException;
import com.elioo.healthcare.core.util.exception.ErrorCode;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public abstract class BaseRepositoryCustomImpl<T> implements GenericRepositoryCustom<T> {

    private final R2dbcEntityTemplate entityTemplate;
    private final DatabaseClient databaseClient;
    private final Class<T> entityClass;
    private final String tableName;

    public BaseRepositoryCustomImpl(R2dbcEntityTemplate entityTemplate, DatabaseClient databaseClient, Class<T> entityClass, String tableName) {
        this.entityTemplate = entityTemplate;
        this.databaseClient = databaseClient;
        this.entityClass = entityClass;
        this.tableName = tableName.toLowerCase(); // Ensure lowercase for consistency
    }

    @Override
    public Flux<T> selectByCriteria(Criteria criteria, Pageable pageable) {
        Query query = Query.query(criteria)
                .sort(pageable.getSort())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());
        return entityTemplate.select(query, entityClass);
    }

    @Override
    public Mono<Long> countByCriteria(Criteria criteria) {
        return entityTemplate.count(Query.query(criteria), entityClass);
    }

    @Override
    public Mono<T> update(String id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return entityTemplate.selectOne(Query.query(Criteria.where("id").is(id)), entityClass)
                    .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND, "Resource with ID " + id + " not found")));
        }
        String setClause = fields.keySet().stream()
                .map(field -> field + " = :" + field)
                .collect(Collectors.joining(", "));
        String query = "UPDATE " + tableName + " SET " + setClause + " WHERE id = :id RETURNING *";
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(query).bind("id", id);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            spec = spec.bind(entry.getKey(), entry.getValue());
        }
        return spec.map(this::mapRowToEntity).one();
    }

    @Override
    public Flux<T> updateByFilter(Map<String, Object> filterCriteria, Map<String, Object> updateFields) {
        if (filterCriteria.isEmpty()) {
            log.error("Filter criteria cannot be empty");
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Filter criteria cannot be empty"));
        }
        if (updateFields.isEmpty()) {
            log.error("Update fields cannot be empty");
            return Flux.error(new AppException(ErrorCode.INVALID_REQUEST, "Update fields cannot be empty"));
        }

        // Build SET clause with snake_case field names
        String setClause = updateFields.keySet().stream()
                .map(field -> CommonFunctions.camelToSnake(field) + " = :" + field)
                .collect(Collectors.joining(", "));

        // Build WHERE clause with snake_case field names
        String whereClause = filterCriteria.keySet().stream()
                .map(field -> CommonFunctions.camelToSnake(field) + " = :" + field)
                .collect(Collectors.joining(" AND "));

        String query = String.format("UPDATE %s SET %s WHERE %s RETURNING *", tableName, setClause, whereClause);

        log.info("Update by filter Query:  {}",query);

        // Combine filterCriteria and updateFields for binding
        Map<String, Object> bindValues = new HashMap<>();
        bindValues.putAll(filterCriteria);
        bindValues.putAll(updateFields);

        return databaseClient.sql(query)
                .bindValues(bindValues)
                .map(this::mapRowToEntity)
                .all()
                .switchIfEmpty(Flux.error(new AppException(ErrorCode.NOT_FOUND, "No resources found matching filter criteria")));
    }

    private T mapRowToEntity(Row row, RowMetadata metadata) {
        try {
            T entity = entityClass.getDeclaredConstructor().newInstance();
            for (Field field : entityClass.getDeclaredFields()) {
                field.setAccessible(true);
                String columnName = CommonFunctions.camelToSnake(field.getName());
                Object value = row.get(columnName, field.getType());
                field.set(entity, value);
            }
            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to entity", e);
        }
    }

}