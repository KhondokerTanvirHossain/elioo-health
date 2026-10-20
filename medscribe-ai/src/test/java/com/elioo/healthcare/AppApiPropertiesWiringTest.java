package com.elioo.healthcare;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployable's own line, not a Binder fed the property key: {@code application.properties} maps
 * {@code BAYMAX_WEB_PUBLIC_ORIGIN} and {@code BAYMAX_WEB_PREVIEW_FAMILIES} onto {@code baymax.web.*}. A typo in the key
 * or the env name would leave preview permanently off (or the origin on its default) with every Baymax test green.
 */
class AppApiPropertiesWiringTest {

    @Test
    void theEnvironmentVariablesReachBaymaxWebThroughApplicationProperties() throws Exception {
        UUID family = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4500001");
        BaymaxProperties.Web web = bind(Map.of("BAYMAX_WEB_PREVIEW_FAMILIES", family.toString(),
                "BAYMAX_WEB_PUBLIC_ORIGIN", "http://x"));
        assertThat(web.getPublicOrigin()).isEqualTo("http://x");
        assertThat(web.previewFamilies()).containsExactly(family);
    }

    /** Both directions: with the variables absent, the defaults are production's origin and nobody previewing. */
    @Test
    void withTheVariablesAbsentTheDefaultsHold() throws Exception {
        BaymaxProperties.Web web = bind(Map.of());
        assertThat(web.getPublicOrigin()).isEqualTo("https://medioo.eliooo.org");
        assertThat(web.previewFamilies()).isEmpty();
    }

    private static BaymaxProperties.Web bind(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        for (PropertySource<?> source : new PropertiesPropertySourceLoader()
                .load("application.properties", new ClassPathResource("application.properties"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bindOrCreate("baymax.web", BaymaxProperties.Web.class);
    }
}
