package com.imin.iminapi.audienceplan.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Every audience-plan class that can reach an LLM must also hold the payload guard. */
class LlmCallersUseGuardTest {

    private static final String PACKAGE = "com.imin.iminapi.audienceplan";

    @Test
    void everyAudiencePlanLlmCaller_holdsTheGuard() throws Exception {
        List<Class<?>> scanned = classesIn(PACKAGE, false);
        assertThat(scanned).contains(LlmPayloadGuard.class).doesNotContain(ConditionalCallerWithoutGuard.class);
        assertThat(scanned).filteredOn(c -> !followsRule(c)).isEmpty();
    }

    @Test
    void scan_keepsConditionalClasses_andFlagsThem() throws Exception {
        List<Class<?>> withTests = classesIn(PACKAGE, true);
        assertThat(withTests).contains(ConditionalCallerWithoutGuard.class);
        assertThat(followsRule(ConditionalCallerWithoutGuard.class)).isFalse();
    }

    @Test
    void rule_flagsEveryLlmHandleWithoutGuard() {
        assertThat(List.of(CallerWithoutGuard.class, ModelCallerWithoutGuard.class, BuilderHolder.class,
                StreamingHolder.class, ProviderHolder.class, SupplierHolder.class, OptionalHolder.class,
                ListOfProvidersHolder.class, ConstructorOnlyCaller.class))
                .allSatisfy(c -> assertThat(followsRule(c)).as(c.getSimpleName()).isFalse());
    }

    @Test
    void rule_acceptsGuardedCallersAndNonCallers() {
        assertThat(List.of(GuardedCaller.class, SubclassOfGuardedCaller.class, NotACaller.class,
                ProviderWithGuardProvider.class, ConstructorGuardedCaller.class))
                .allSatisfy(c -> assertThat(followsRule(c)).as(c.getSimpleName()).isTrue());
    }

    static boolean followsRule(Class<?> c) {
        List<Type> types = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) types.add(f.getGenericType());
            for (Constructor<?> ctor : k.getDeclaredConstructors()) types.addAll(List.of(ctor.getGenericParameterTypes()));
        }
        boolean llm = types.stream().anyMatch(t -> mentions(t, LlmCallersUseGuardTest::isLlmHandle));
        boolean guard = types.stream().anyMatch(t -> mentions(t, LlmPayloadGuard.class::isAssignableFrom));
        return !llm || guard;
    }

    private static boolean isLlmHandle(Class<?> t) {
        return ChatClient.class.isAssignableFrom(t) || ChatClient.Builder.class.isAssignableFrom(t)
                || ChatModel.class.isAssignableFrom(t) || StreamingChatModel.class.isAssignableFrom(t);
    }

    /** True when the type or any of its generic arguments, bounds or array components matches. */
    private static boolean mentions(Type t, Predicate<Class<?>> match) {
        if (t instanceof Class<?> c) {
            return c.isArray() ? mentions(c.getComponentType(), match) : match.test(c);
        }
        if (t instanceof ParameterizedType p) {
            if (mentions(p.getRawType(), match)) return true;
            for (Type a : p.getActualTypeArguments()) if (mentions(a, match)) return true;
            return false;
        }
        if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) if (mentions(b, match)) return true;
            for (Type b : w.getLowerBounds()) if (mentions(b, match)) return true;
            return false;
        }
        if (t instanceof GenericArrayType g) return mentions(g.getGenericComponentType(), match);
        return false;
    }

    // Reads every .class under the package directly, so @Conditional/@Profile classes are not filtered out.
    private static List<Class<?>> classesIn(String pkg, boolean includeTests) throws IOException, ClassNotFoundException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> out = new ArrayList<>();
        for (Resource r : resolver.getResources("classpath*:" + pkg.replace('.', '/') + "/**/*.class")) {
            if (!includeTests && r.getURL().toString().contains("test-classes")) continue;
            String name = readers.getMetadataReader(r).getClassMetadata().getClassName();
            out.add(Class.forName(name, false, LlmCallersUseGuardTest.class.getClassLoader()));
        }
        return out;
    }

    @ConditionalOnProperty("imin.never-set-in-tests")
    static class ConditionalCallerWithoutGuard {
        ChatClient chat;
    }

    static class CallerWithoutGuard {
        ChatClient chat;
    }

    static class ModelCallerWithoutGuard {
        ChatModel model;
    }

    static class BuilderHolder {
        ChatClient.Builder builder;
    }

    static class StreamingHolder {
        StreamingChatModel model;
    }

    static class ProviderHolder {
        ObjectProvider<ChatClient> chat;
    }

    static class SupplierHolder {
        Supplier<ChatModel> model;
    }

    static class OptionalHolder {
        Optional<ChatClient> chat;
    }

    static class ListOfProvidersHolder {
        List<ObjectProvider<? extends ChatModel>> models;
    }

    static class ConstructorOnlyCaller {
        ConstructorOnlyCaller(ChatClient.Builder builder) {
        }
    }

    static class GuardedCaller {
        ChatClient chat;
        LlmPayloadGuard guard;
    }

    static class SubclassOfGuardedCaller extends GuardedCaller {
    }

    static class NotACaller {
        String text;
    }

    static class ProviderWithGuardProvider {
        ObjectProvider<ChatClient> chat;
        ObjectProvider<LlmPayloadGuard> guard;
    }

    static class ConstructorGuardedCaller {
        ConstructorGuardedCaller(ChatClient chat, LlmPayloadGuard guard) {
        }
    }
}
