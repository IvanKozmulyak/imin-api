package com.imin.iminapi.support;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Flips a property on a {@code @ConfigurationProperties} bean and restores the raw field after the test.
 * Only for values read per call; a value captured at construction does not see the flip.
 */
public final class PropertyFlips {

    private record Snapshot(Object owner, String field, Object value) {}

    private final Deque<Snapshot> snapshots = new ArrayDeque<>();

    public synchronized void set(Object propertiesBean, String path, Object value) {
        Class<?> type = ClassUtils.getUserClass(propertiesBean);
        if (!AnnotatedElementUtils.hasAnnotation(type, ConfigurationProperties.class)) {
            throw new IllegalArgumentException(type.getName() + " is not a @ConfigurationProperties bean");
        }
        int dot = path.lastIndexOf('.');
        BeanWrapperImpl root = new BeanWrapperImpl(propertiesBean);
        Object owner = dot < 0 ? propertiesBean : root.getPropertyValue(path.substring(0, dot));
        if (owner == null) throw new IllegalArgumentException("no object at " + path.substring(0, dot));
        String field = path.substring(dot + 1);
        // The raw field, not the getter: a getter may derive a fallback that must not be written back.
        Object before = ReflectionTestUtils.getField(owner, field);
        root.setPropertyValue(path, value);
        snapshots.push(new Snapshot(owner, field, before));
    }

    public synchronized void restoreAll() {
        while (!snapshots.isEmpty()) {
            Snapshot s = snapshots.pop();
            ReflectionTestUtils.setField(s.owner(), s.field(), s.value());
        }
    }
}
