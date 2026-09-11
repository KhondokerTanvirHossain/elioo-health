package com.elioo.healthcare.core.base.adapter.out.persistence.repository;

import com.elioo.healthcare.core.base.adapter.out.persistence.entity.BaseEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface GenericRepository<T extends BaseEntity, ID extends String> extends R2dbcRepository<T, ID>, GenericRepositoryCustom<T> {
}