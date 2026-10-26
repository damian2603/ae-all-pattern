package io.github.langqi99.aeallpattern.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class ReflectTest {
    private static class ParentProvider {
        private int refreshes;

        protected void updatePatterns() {
            refreshes++;
        }
    }

    private static final class ChildProvider extends ParentProvider {
    }

    @Test
    void findsInheritedProtectedRefreshMethod() throws Exception {
        ChildProvider provider = new ChildProvider();
        Reflect.invokeDeclared(provider, "updatePatterns");
        assertEquals(1, ((ParentProvider) provider).refreshes);
    }

    @Test
    void reportsMissingRefreshMethodInsteadOfSilentlyDoingNothing() {
        assertThrows(NoSuchMethodException.class,
                () -> Reflect.invokeDeclared(new ChildProvider(), "missingUpdate"));
    }

    @Test
    void preservesTargetException() {
        class ThrowingProvider {
            private void updatePatterns() { throw new IllegalStateException("provider not ready"); }
        }
        assertThrows(InvocationTargetException.class,
                () -> Reflect.invokeDeclared(new ThrowingProvider(), "updatePatterns"));
    }
}
