package com.imin.iminapi.support;

import org.junit.jupiter.api.Nested;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.PropertyMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBeans;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBeans;
import org.springframework.util.ClassUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds test classes that boot a Spring context other than a named one, or change the named one. */
final class SpringContextGuard {

    /** Every named context. Adding one is an explicit decision, with its reason in that annotation's javadoc. */
    static final List<Class<? extends Annotation>> NAMED_CONTEXTS = List.of(IminIntegrationTest.class);

    private static final List<Class<? extends Annotation>> FORBIDDEN_CLASS_ANNOTATIONS = List.of(
            SpringBootTest.class, BootstrapWith.class, ContextConfiguration.class, ContextHierarchy.class,
            TestPropertySource.class, ActiveProfiles.class, Import.class, DirtiesContext.class,
            MockitoBean.class, MockitoBeans.class, MockitoSpyBean.class, MockitoSpyBeans.class,
            TestExecutionListeners.class, ImportAutoConfiguration.class, PropertyMapping.class,
            Testcontainers.class, ImportTestcontainers.class);

    private static final List<Class<? extends Annotation>> FORBIDDEN_FIELD_ANNOTATIONS = List.of(
            MockitoBean.class, MockitoSpyBean.class, TestBean.class, Container.class, ServiceConnection.class);

    private static final String OWN_FIXTURES_PREFIX = "com.imin.iminapi.support.SpringContextGuardTest$";

    enum Kind { LEGACY, FORBIDDEN }

    record Violation(Kind kind, String detail) {}

    private SpringContextGuard() {}

    static boolean boots(Class<?> c) {
        return TestContextAnnotationUtils.hasAnnotation(c, BootstrapWith.class)
                || TestContextAnnotationUtils.hasAnnotation(c, ContextConfiguration.class)
                || extendsWithSpring(c);
    }

    // ponytail: annotation scan only; a context built in a test method (new AnnotationConfigApplicationContext,
    // SpringApplication) is invisible to it.
    private static boolean extendsWithSpring(Class<?> c) {
        for (Class<?> level = c; level != null; level = level.getEnclosingClass()) {
            if (MergedAnnotations.from(level, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .stream(ExtendWith.class)
                    .anyMatch(a -> List.of(a.getClassArray("value")).contains(SpringExtension.class))) {
                return true;
            }
        }
        return false;
    }

    static boolean named(Class<?> c) {
        return NAMED_CONTEXTS.stream().anyMatch(a -> TestContextAnnotationUtils.hasAnnotation(c, a));
    }

    static List<Violation> violations(Class<?> c) {
        if (!boots(c)) return List.of();
        if (!named(c)) {
            return List.of(new Violation(Kind.LEGACY, "boots a Spring context without a named annotation"));
        }
        List<Violation> found = new ArrayList<>();
        for (Class<?> level : hierarchy(c)) {
            for (Annotation a : level.getDeclaredAnnotations()) {
                Class<? extends Annotation> type = a.annotationType();
                if (!NAMED_CONTEXTS.contains(type) && changesContext(type)) {
                    found.add(new Violation(Kind.FORBIDDEN,
                            "@" + type.getSimpleName() + " on " + level.getName() + " changes the named context"));
                }
            }
            for (Field f : level.getDeclaredFields()) {
                annotatedWith(f, FORBIDDEN_FIELD_ANNOTATIONS).forEach(type -> found.add(new Violation(Kind.FORBIDDEN,
                        "@" + type.getSimpleName() + " on field " + level.getName() + "." + f.getName())));
            }
            // Boot adds a nested @Configuration/@TestConfiguration to the context, so it changes the cache key.
            for (Class<?> nested : level.getDeclaredClasses()) {
                if (AnnotatedElementUtils.hasAnnotation(nested, Configuration.class)) {
                    found.add(new Violation(Kind.FORBIDDEN,
                            "nested @Configuration " + nested.getName() + " changes the named context"));
                }
            }
            for (Method m : level.getDeclaredMethods()) {
                if (m.isAnnotationPresent(DynamicPropertySource.class)) {
                    found.add(new Violation(Kind.FORBIDDEN,
                            "@DynamicPropertySource on " + level.getName() + "." + m.getName()));
                }
            }
        }
        return found;
    }

    /** Every scanned class that boots a context other than a named one, or changes the named one. */
    static List<String> check(List<Class<?>> scanned) {
        List<String> findings = new ArrayList<>();
        for (Class<?> c : scanned) {
            String name = c.getName();
            for (Violation v : violations(c)) {
                findings.add(v.kind() == Kind.LEGACY
                        ? name + ": boots its own Spring context; use @IminIntegrationTest"
                        : name + ": " + v.detail());
            }
        }
        return findings;
    }

    /** Every class surefire would run from the test output directory, except this guard's own fixtures. */
    static List<Class<?>> scanTestClasses() {
        Path root;
        try {
            root = Path.of(SpringContextGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        ClassLoader loader = SpringContextGuard.class.getClassLoader();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> names = files
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString())
                    .map(s -> s.substring(0, s.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .filter(n -> !n.endsWith("package-info") && !n.endsWith("module-info"))
                    .filter(n -> !n.startsWith(OWN_FIXTURES_PREFIX))
                    .sorted()
                    .toList();
            List<Class<?>> classes = new ArrayList<>();
            for (String n : names) {
                Class<?> c;
                try {
                    c = Class.forName(n, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("cannot load " + n, e);
                }
                if (runnable(c)) classes.add(c);
            }
            return classes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ponytail: top-level and @Nested classes only, the ones surefire runs; a static nested @SpringBootTest is not seen.
    private static boolean runnable(Class<?> c) {
        if (c.isAnnotation() || c.isSynthetic() || c.isAnonymousClass() || c.isLocalClass()) return false;
        if (c.getEnclosingClass() == null) return true;
        return ClassUtils.isInnerClass(c) && c.isAnnotationPresent(Nested.class);
    }

    private static List<Class<?>> hierarchy(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Class<?> c, List<Class<?>> out) {
        if (c == null || c == Object.class || out.contains(c)) return;
        out.add(c);
        collect(c.getSuperclass(), out);
        if (ClassUtils.isInnerClass(c)) collect(c.getEnclosingClass(), out);
    }

    private static boolean changesContext(Class<? extends Annotation> type) {
        MergedAnnotations meta = MergedAnnotations.from(type);
        return FORBIDDEN_CLASS_ANNOTATIONS.stream().anyMatch(f -> f == type || meta.isPresent(f));
    }

    private static List<Class<? extends Annotation>> annotatedWith(AnnotatedElement e,
                                                                   List<Class<? extends Annotation>> types) {
        return types.stream().filter(e::isAnnotationPresent).toList();
    }
}
