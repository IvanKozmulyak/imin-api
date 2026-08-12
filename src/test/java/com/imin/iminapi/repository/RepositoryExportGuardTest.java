package com.imin.iminapi.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Spring Data repository in this tree must carry
 * {@code @RepositoryRestResource(exported = false)}.
 *
 * <p>Why this is a test and not a convention: Spring Data REST is on the
 * classpath, {@code spring.data.rest.base-path} is {@code /api/v1}, and no
 * detection strategy is configured — so the default strategy auto-publishes
 * every public repository interface as a REST resource. Those generated
 * endpoints land inside {@code /api/v1/**}, which {@code SecurityConfig} only
 * requires {@code .authenticated()} on: no tenant check, no buyer check, no
 * org filter. Any authenticated organizer could read another org's rows —
 * refund magic-link tokens included — and nothing in review reliably catches a
 * missing annotation on a new file.
 *
 * <p>A failure here is not a style nit. Add the annotation.
 */
class RepositoryExportGuardTest {

    private static final String BASE_PACKAGE = "com.imin.iminapi";

    @Test
    void every_repository_interface_disables_spring_data_rest_export() {
        List<Class<?>> repositories = findRepositoryInterfaces();

        // Sanity check on the scanner itself: if the scan silently found
        // nothing, the guard would pass forever without checking anything.
        assertThat(repositories)
                .as("classpath scan found no repository interfaces — the scan is broken, not the code")
                .hasSizeGreaterThan(40);

        List<String> unguarded = new ArrayList<>();
        for (Class<?> repo : repositories) {
            RepositoryRestResource annotation = repo.getAnnotation(RepositoryRestResource.class);
            if (annotation == null || annotation.exported()) {
                unguarded.add(repo.getName());
            }
        }

        assertThat(unguarded)
                .as("repositories missing @RepositoryRestResource(exported = false) — "
                        + "Spring Data REST would publish them under /api/v1 with no tenant check")
                .isEmpty();
    }

    private static List<Class<?>> findRepositoryInterfaces() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                // The default implementation rejects interfaces; repositories are
                // exactly the interfaces we care about.
                return beanDefinition.getMetadata().isInterface()
                        && beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = candidate.getBeanClassName();
            if (name == null) continue;
            Class<?> type = ClassUtils.resolveClassName(name, RepositoryExportGuardTest.class.getClassLoader());
            if (Repository.class.isAssignableFrom(type) && type.isInterface()) {
                found.add(type);
            }
        }
        return found;
    }
}
