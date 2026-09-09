package org.junit.jupiter.api;

import java.lang.annotation.*;

/** Minimal stand-in for {@code org.junit.jupiter.api.AfterEach}. See {@link Test}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterEach {
}
