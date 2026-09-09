package org.junit.jupiter.api.io;

import java.lang.annotation.*;

/**
 * Minimal stand-in for {@code org.junit.jupiter.api.io.TempDir}. A parameter
 * (of type {@code java.nio.file.Path}) carrying this annotation is supplied a
 * fresh, real temporary directory by {@code com.marketplace.TestRunner}, one
 * per test invocation (or once per class when used on a {@code @BeforeAll}
 * method), mirroring real JUnit 5's lifecycle. See {@code Test}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface TempDir {
}
