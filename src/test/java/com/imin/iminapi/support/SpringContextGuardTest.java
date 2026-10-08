package com.imin.iminapi.support;

import com.imin.iminapi.support.SpringContextGuard.Kind;
import com.imin.iminapi.support.SpringContextGuard.Violation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The one-context rule and the shrink-only allow-list. Fixture classes are nested and never run. */
class SpringContextGuardTest {

    private static final String ALLOW_LIST = "test-guard/legacy-spring-tests.txt";

    // ── fixtures ────────────────────────────────────────────────────────────

    static class Plain {}

    @SpringBootTest static class LegacyBoot {}
    @DataJpaTest static class LegacySlice {}
    @ContextConfiguration static class LegacyContextConfig {}

    @SpringBootTest static class LegacyOuter {
        @Nested class Inner {}
    }

    @IminIntegrationTest static class Clean {}

    @IminIntegrationTest @TestPropertySource(properties = "a=b") static class WithPropertySource {}
    @IminIntegrationTest @SpringBootTest(properties = "a=b") static class WithBootProperties {}
    @IminIntegrationTest @Import(Plain.class) static class WithImport {}
    @IminIntegrationTest @DirtiesContext static class WithDirtiesContext {}
    @IminIntegrationTest @MockitoBean(types = Runnable.class) static class WithTypeMockitoBean {}
    @IminIntegrationTest @ActiveProfiles("other") static class WithActiveProfiles {}
    @IminIntegrationTest @Testcontainers static class WithTestcontainers {}
    @IminIntegrationTest @AutoConfigureMockMvc(addFilters = false) static class WithMockMvcOverride {}

    @IminIntegrationTest static class WithMockitoBeanField { @MockitoBean Runnable r; }
    @IminIntegrationTest static class WithMockitoSpyBeanField { @MockitoSpyBean Runnable r; }
    @IminIntegrationTest static class WithTestBeanField {
        @TestBean Runnable r;
        static Runnable r() { return () -> {}; }
    }
    @IminIntegrationTest static class WithContainerField { @Container static Object pg = new Object(); }

    @IminIntegrationTest static class WithDynamicProperties {
        @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {}
    }

    @IminIntegrationTest static class WithNestedConfig {
        @TestConfiguration static class Cfg {}
    }
    @IminIntegrationTest static class WithNestedPlainConfig {
        @Configuration static class Cfg {}
    }

    @TestPropertySource(properties = "a=b") static class ForbiddenBase {}
    @IminIntegrationTest static class SubOfForbidden extends ForbiddenBase {}

    private static Class<?> fixture(String simpleName) throws ClassNotFoundException {
        return Class.forName(SpringContextGuardTest.class.getName() + "$" + simpleName);
    }

    private static List<Kind> kinds(Class<?> c) {
        return SpringContextGuard.violations(c).stream().map(Violation::kind).toList();
    }

    // ── violations ──────────────────────────────────────────────────────────

    @Test
    void plainClass_hasNoViolation() {
        assertThat(SpringContextGuard.violations(Plain.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LegacyBoot", "LegacySlice", "LegacyContextConfig"})
    void bootingClassWithoutNamedContext_isLegacy(String name) throws Exception {
        assertThat(kinds(fixture(name))).containsExactly(Kind.LEGACY);
    }

    @Test
    void cleanNamedClass_hasNoViolation() {
        assertThat(SpringContextGuard.violations(Clean.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WithPropertySource", "WithBootProperties", "WithImport", "WithDirtiesContext",
            "WithTypeMockitoBean", "WithActiveProfiles", "WithTestcontainers", "WithMockMvcOverride"})
    void namedClassWithContextChangingAnnotation_isForbidden(String name) throws Exception {
        assertThat(kinds(fixture(name))).isNotEmpty().containsOnly(Kind.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WithMockitoBeanField", "WithMockitoSpyBeanField", "WithTestBeanField",
            "WithContainerField"})
    void namedClassWithOverrideField_isForbidden(String name) throws Exception {
        assertThat(kinds(fixture(name))).isNotEmpty().containsOnly(Kind.FORBIDDEN);
    }

    @Test
    void namedClassWithDynamicPropertySource_isForbidden() {
        assertThat(kinds(WithDynamicProperties.class)).isNotEmpty().containsOnly(Kind.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WithNestedConfig", "WithNestedPlainConfig"})
    void namedClassWithNestedConfiguration_isForbidden(String name) throws Exception {
        assertThat(kinds(fixture(name))).isNotEmpty().containsOnly(Kind.FORBIDDEN);
    }

    @Test
    void namedSubclassOfForbiddenSuperclass_isForbidden() {
        assertThat(kinds(SubOfForbidden.class)).isNotEmpty().containsOnly(Kind.FORBIDDEN);
    }

    @Test
    void nestedClassOfLegacyClass_isLegacy() {
        assertThat(kinds(LegacyOuter.Inner.class)).containsExactly(Kind.LEGACY);
    }

    // ── allow-list check ────────────────────────────────────────────────────

    @Test
    void check_reportsUnlistedViolator() {
        List<String> findings = SpringContextGuard.check(List.of(LegacyBoot.class, Plain.class), List.of());
        assertThat(findings).singleElement().asString().contains(LegacyBoot.class.getName());
    }

    @Test
    void check_reportsListedNonViolatorAsStale() {
        List<String> findings = SpringContextGuard.check(List.of(Plain.class), List.of(Plain.class.getName()));
        assertThat(findings).singleElement().asString()
                .contains(Plain.class.getName()).contains("remove from allow-list");
    }

    @Test
    void check_reportsListedUnknownClass() {
        List<String> findings = SpringContextGuard.check(List.of(Plain.class), List.of("com.example.Gone"));
        assertThat(findings).singleElement().asString().contains("com.example.Gone");
    }

    @Test
    void check_reportsUnsortedOrDuplicatedList() {
        String a = LegacyBoot.class.getName();
        String b = LegacySlice.class.getName();
        List<Class<?>> scanned = List.of(LegacyBoot.class, LegacySlice.class);

        assertThat(SpringContextGuard.check(scanned, List.of(a, b))).isEmpty();
        assertThat(SpringContextGuard.check(scanned, List.of(b, a))).singleElement().asString().contains("sorted");
        assertThat(SpringContextGuard.check(scanned, List.of(a, a, b))).isNotEmpty()
                .anySatisfy(f -> assertThat(f).contains("sorted"));
    }

    // ── the real suite ──────────────────────────────────────────────────────

    @Test
    void suite_matchesTheCheckedInAllowList() throws IOException {
        List<Class<?>> scanned = SpringContextGuard.scanTestClasses();
        // An empty or truncated scan would pass against an empty allow-list.
        assertThat(scanned).extracting(Class::getName).contains(
                SpringContextGuardTest.class.getName(), "com.imin.iminapi.app.AppConfigControllerTest");
        List<String> findings = SpringContextGuard.check(scanned, readAllowList());
        assertThat(findings).as("%d finding(s):%n%s", findings.size(), String.join("\n", findings)).isEmpty();
    }

    private static List<String> readAllowList() throws IOException {
        try (InputStream in = SpringContextGuardTest.class.getClassLoader().getResourceAsStream(ALLOW_LIST)) {
            assertThat(in).as(ALLOW_LIST + " on the test classpath").isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(text.split("\n"))
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }
}
