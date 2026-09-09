package org.junit.jupiter.api;

import java.lang.annotation.*;

/**
 * Minimal stand-in for {@code org.junit.jupiter.api.Test}, source-compatible
 * with the real JUnit Jupiter annotation. This sandbox cannot reach Maven
 * Central to fetch the real junit-jupiter artifact, so this shim (plus its
 * siblings in this package) lets the project's existing JUnit-5-style test
 * sources compile and run unmodified, driven by {@code com.marketplace.TestRunner}
 * instead of the real JUnit platform. Swap in the real dependency (see pom.xml)
 * on a machine with normal internet access and these tests run under actual
 * JUnit 5 without any changes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
}
