package com.elioo.healthcare.core.base.adapter.out.persistence.criteria;

import org.springframework.data.relational.core.query.Criteria;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DynamicCriteriaBuilder {

    private static Class<?> getFieldType(Class<?> entityClass, String fieldName) {
        try {
            Field field = entityClass.getDeclaredField(fieldName);
            return field.getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Invalid field name: " + fieldName, e);
        }
    }

    public static Criteria buildSingleValueCriteria(Class<?> entityClass, Map<String, String> filters) {
        if (filters.isEmpty()) {
            return Criteria.empty();
        }

        return filters.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    Class<?> fieldType = getFieldType(entityClass, key);

                    if (fieldType.equals(Integer.class)) {
                        return Criteria.where(key).is(Integer.parseInt(value));
                    } else if (fieldType.equals(Long.class)) {
                        return Criteria.where(key).is(Long.parseLong(value));
                    } else if (fieldType.equals(Double.class)) {
                        return Criteria.where(key).is(Double.parseDouble(value));
                    } else {
                        return Criteria.where(key).is(value);
                    }
                })
                .reduce(Criteria::and)
                .orElse(Criteria.empty());
    }

    public static Criteria buildListValueCriteria(Class<?> entityClass, Map<String, List<String>> filters) {
        if (filters.isEmpty()) {
            return Criteria.empty();
        }

        return filters.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    List<String> values = entry.getValue();
                    Class<?> fieldType = getFieldType(entityClass, key);

                    if (fieldType.equals(Integer.class)) {
                        List<Integer> intValues = values.stream().map(Integer::parseInt).collect(Collectors.toList());
                        return Criteria.where(key).in(intValues);
                    } else if (fieldType.equals(Long.class)) {
                        List<Long> longValues = values.stream().map(Long::parseLong).collect(Collectors.toList());
                        return Criteria.where(key).in(longValues);
                    } else if (fieldType.equals(Double.class)) {
                        List<Double> doubleValues = values.stream().map(Double::parseDouble).collect(Collectors.toList());
                        return Criteria.where(key).in(doubleValues);
                    } else {
                        return Criteria.where(key).in(values);
                    }
                })
                .reduce(Criteria::and)
                .orElse(Criteria.empty());
    }
}