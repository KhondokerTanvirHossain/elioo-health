package com.elioo.healthcare.core.base.adapter.out.persistence.repository;

import com.elioo.healthcare.core.base.adapter.out.persistence.entity.BaseEntity;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

public abstract class BaseRepository<T extends BaseEntity> extends BaseRepositoryCustomImpl<T> implements GenericRepository<T, String> {

    protected BaseRepository(R2dbcEntityTemplate entityTemplate, DatabaseClient databaseClient, Class<T> entityClass, String tableName) {
        super(entityTemplate, databaseClient, entityClass, tableName);
    }
}