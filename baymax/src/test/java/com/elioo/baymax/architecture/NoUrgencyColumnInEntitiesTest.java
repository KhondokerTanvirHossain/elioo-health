package com.elioo.baymax.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1/F2: only PostgresDocumentUrgencyAdapter writes urgency and verdict columns; an entity may at most read one. A
 * writable entity property would let any {@code save()} overwrite the stored level with whatever the entity held.
 * Derived over every {@code @Table} class, never a list.
 */
class NoUrgencyColumnInEntitiesTest {

    @Test
    void noEntityHasAWritableUrgencyOrVerdictProperty() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.elioo.baymax");
        List<String> entities = new ArrayList<>();
        List<String> writable = new ArrayList<>();
        classes.stream().filter(c -> c.isAnnotatedWith(Table.class)).forEach(c -> {
            entities.add(c.getSimpleName());
            for (JavaField f : c.getFields()) {
                // a bare @Column has an empty value and maps to the field name, as Spring Data does
                String column = f.tryGetAnnotationOfType(Column.class).map(Column::value).filter(v -> !v.isBlank())
                        .orElse(f.getName());
                if (column.matches("(?i)(urgency|verdict)[a-z_]*") && !f.isAnnotatedWith(ReadOnlyProperty.class)) {
                    writable.add(c.getSimpleName() + "." + f.getName());
                }
            }
        });
        assertThat(entities).as("entity classes found").contains("DocumentEntity", "ObservationEntity");
        assertThat(writable).isEmpty();
    }
}
