package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.config.PredictorProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * predictor-edge-2: a {@code @ConfigurationProperties} field that {@code application.yaml} does
 * not enumerate is only reachable under its relaxed-binding name
 * ({@code IMIN_PREDICTOR_WEATHER_ENABLED}), never under the {@code PREDICTOR_*} name the code
 * documents — so setting the documented variable changes nothing and reports nothing.
 *
 * <p>The weather kill switch was the live example: {@code PredictorProperties.weatherEnabled}
 * said "Bound to {@code ${PREDICTOR_WEATHER_ENABLED:true}}" while the {@code imin.predictor}
 * block stopped at the SCORE phase, so turning the outbound Open-Meteo call off in production
 * needed a deploy. This is the same trap CLAUDE.md records for
 * {@code GOOGLE_OAUTH_NATIVE_AUDIENCE} ("without the line Spring would bind only IMIN_… and this
 * name would be silently inert").
 *
 * <p>Two guards, both read from source so they cannot be satisfied by a permissive test double:
 * every property field must appear in the yaml block, and every {@code PREDICTOR_*} variable a
 * javadoc in that class names must appear there too.
 */
class PredictorPropertiesBindingTest {

    private static final Pattern ENV_NAME = Pattern.compile("PREDICTOR_[A-Z0-9_]+");

    @Test
    void every_predictor_property_is_enumerated_in_application_yaml() throws IOException {
        String block = predictorBlock();

        Set<String> missing = new TreeSet<>();
        for (Field f : PredictorProperties.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
            String key = kebab(f.getName());
            if (!block.contains("\n    " + key + ":")) missing.add(key);
        }

        assertThat(missing)
                .as("""
                    These imin.predictor properties are not listed in application.yaml. Spring
                    then binds them ONLY under their relaxed name (IMIN_PREDICTOR_…), so the
                    PREDICTOR_… variable the code documents is silently inert and the knob can
                    only be changed by a deploy. Add the `key: ${PREDICTOR_…:default}` line, or
                    delete the property.""")
                .isEmpty();
    }

    @Test
    void every_documented_predictor_env_var_is_bound() throws IOException {
        String block = predictorBlock();
        String source = Files.readString(Path.of(
                "src/main/java/com/imin/iminapi/predictor/config/PredictorProperties.java"),
                StandardCharsets.UTF_8);

        Set<String> documented = new LinkedHashSet<>();
        Matcher m = ENV_NAME.matcher(source);
        while (m.find()) documented.add(m.group());

        Set<String> unbound = new TreeSet<>();
        for (String name : documented) {
            if (!block.contains("${" + name + ":")) unbound.add(name);
        }

        assertThat(documented).isNotEmpty();
        assertThat(unbound)
                .as("PredictorProperties names these environment variables but application.yaml "
                        + "binds no placeholder for them — reading the javadoc would mislead an operator.")
                .isEmpty();
    }

    /** The {@code imin.predictor} block of application.yaml, up to the next same-level key. */
    private static String predictorBlock() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8);
        int start = yaml.indexOf("\n  predictor:");
        assertThat(start).as("application.yaml has no imin.predictor block").isGreaterThan(0);
        Matcher next = Pattern.compile("\n  [a-z][a-z0-9-]*:").matcher(yaml);
        int end = next.find(start + 1) ? next.start() : yaml.length();
        return yaml.substring(start, end);
    }

    private static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('-').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
