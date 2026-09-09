package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;
import java.util.Objects;

/**
 * Minimal stand-in for {@code org.junit.jupiter.api.Assertions}, covering
 * exactly the assertion methods this project's test sources use. See
 * {@link Test} for why this shim exists.
 */
public final class Assertions {
    private Assertions() {}

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(describe(message, "expected: <" + expected + "> but was: <" + actual + ">"));
        }
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, null);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(describe(message, "expected true but was false"));
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, null);
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError(describe(message, "expected false but was true"));
    }

    public static void assertNull(Object actual) {
        assertNull(actual, null);
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(describe(message, "expected null but was: <" + actual + ">"));
    }

    public static void assertNotNull(Object actual) {
        assertNotNull(actual, null);
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) throw new AssertionError(describe(message, "expected a non-null value"));
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        assertNotEquals(unexpected, actual, null);
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError(describe(message, "expected: not equal but both were: <" + actual + ">"));
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) return expectedType.cast(actual);
            throw new AssertionError("Unexpected exception type thrown: expected <" + expectedType
                + "> but was <" + actual.getClass() + ">", actual);
        }
        throw new AssertionError("Expected " + expectedType.getName() + " to be thrown, but nothing was thrown.");
    }

    public static void assertDoesNotThrow(Executable executable) {
        try {
            executable.execute();
        } catch (Throwable actual) {
            throw new AssertionError("Expected no exception to be thrown, but " + actual.getClass() + " was thrown: " + actual.getMessage(), actual);
        }
    }

    private static String describe(String message, String detail) {
        return (message == null || message.isBlank()) ? detail : message + " ==> " + detail;
    }
}
