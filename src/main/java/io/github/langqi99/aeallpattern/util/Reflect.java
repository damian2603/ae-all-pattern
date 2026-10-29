package io.github.langqi99.aeallpattern.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Tiny reflection helper for addon-targeted mixins.
 *
 * <p>Addon classes change field types between releases, so a {@code @Shadow} of the wrong type
 * fails the whole mixin application and crashes the game at startup. Reflection degrades
 * gracefully instead: a missing member only disables the optional part of a mixin.</p>
 *
 * <p>Field lookup walks the class hierarchy because most targets inherit the members we need.</p>
 *
 * <p>This class must live outside the mixin package declared in {@code aeallpattern.mixins.json}:
 * every class under a mixin package is owned by the mixin config and throws {@code
 * IllegalClassLoadError} the moment a transformed target class references it at runtime.</p>
 */
public final class Reflect {
    private Reflect() {
    }

    /** Reads a declared field from the target or any of its superclasses; {@code null} if absent. */
    public static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** Typed variant of {@link #field(Object, String)}. */
    @SuppressWarnings("unchecked")
    public static <T> T field(Object target, String name, Class<T> expected) {
        Object value = field(target, name);
        return expected.isInstance(value) ? (T) value : null;
    }

    /** Invokes a method resolved against the runtime class, mapping primitives for lookup. */
    public static Object invoke(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = primitive(args[index].getClass());
        }
        Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /** Resolves protected/package-private addon methods, including methods inherited from a superclass. */
    public static Object invokeDeclared(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = primitive(args[index].getClass());
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name + " on " + target.getClass().getName());
    }

    private static Class<?> primitive(Class<?> type) {
        if (type == Integer.class) return int.class;
        if (type == Boolean.class) return boolean.class;
        if (type == Long.class) return long.class;
        if (type == Double.class) return double.class;
        if (type == Float.class) return float.class;
        if (type == Short.class) return short.class;
        if (type == Byte.class) return byte.class;
        if (type == Character.class) return char.class;
        return type;
    }
}
