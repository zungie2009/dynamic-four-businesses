package com.fourbusiness;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers and runs every {@code @Test} method across the compiled test
 * classpath by reflection, honoring {@code @BeforeAll}/{@code @AfterAll}
 * (once per class, static), {@code @BeforeEach}/{@code @AfterEach} (once per
 * test, instance), and injecting a fresh real {@code Path} for any parameter
 * annotated {@code @TempDir} - the same lifecycle real JUnit 5 gives these
 * exact test sources. Exists because this sandbox cannot reach Maven Central
 * to fetch the real junit-jupiter artifact (see the shim classes under
 * {@code org.junit.jupiter.api} in src/testlib): on a machine with normal
 * internet access, {@code mvn test} runs these same files unmodified under
 * real JUnit instead.
 *
 * Usage: {@code java -cp <main classes>:<testlib classes>:<test classes> com.fourbusiness.TestRunner}
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        List<Class<?>> testClasses = discoverTestClasses();
        int totalPassed = 0, totalFailed = 0;
        List<String> failures = new ArrayList<>();

        for (Class<?> testClass : testClasses) {
            Result r = runClass(testClass, failures);
            totalPassed += r.passed;
            totalFailed += r.failed;
        }

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.printf("Ran %d test classes, %d tests: %d passed, %d failed%n",
            testClasses.size(), totalPassed + totalFailed, totalPassed, totalFailed);
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failures:");
            for (String f : failures) System.out.println("  - " + f);
        }
        System.out.println("=".repeat(70));
        if (totalFailed > 0) System.exit(1);
    }

    private record Result(int passed, int failed) {}

    private static Result runClass(Class<?> testClass, List<String> failures) throws Exception {
        List<Method> beforeAll = methodsAnnotatedWith(testClass, BeforeAll.class, true);
        List<Method> afterAll = methodsAnnotatedWith(testClass, AfterAll.class, true);
        List<Method> beforeEach = methodsAnnotatedWith(testClass, BeforeEach.class, false);
        List<Method> afterEach = methodsAnnotatedWith(testClass, AfterEach.class, false);
        List<Method> tests = methodsAnnotatedWith(testClass, Test.class, false);

        if (tests.isEmpty()) return new Result(0, 0);

        int passed = 0, failed = 0;
        Path classTempDir = null;
        try {
            for (Method m : beforeAll) {
                if (classTempDir == null && hasTempDirParam(m)) classTempDir = newTempDir();
                invoke(m, null, resolveParams(m, classTempDir));
            }

            for (Method testMethod : tests) {
                String name = testClass.getSimpleName() + "." + testMethod.getName();
                Path perTestTempDir = null;
                try {
                    Constructor<?> ctor = testClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    if (needsTempDir(beforeEach) || hasTempDirParam(testMethod)) perTestTempDir = newTempDir();

                    for (Method be : beforeEach) invoke(be, instance, resolveParams(be, perTestTempDir));
                    invoke(testMethod, instance, resolveParams(testMethod, perTestTempDir));
                    for (Method ae : afterEach) invoke(ae, instance, resolveParams(ae, perTestTempDir));

                    System.out.println("PASS  " + name);
                    passed++;
                } catch (Throwable t) {
                    Throwable cause = unwrap(t);
                    System.out.println("FAIL  " + name + "  -  " + cause);
                    failures.add(name + ": " + cause);
                    failed++;
                } finally {
                    if (perTestTempDir != null) deleteRecursively(perTestTempDir);
                }
            }
        } finally {
            for (Method m : afterAll) {
                try { invoke(m, null, resolveParams(m, classTempDir)); } catch (Throwable ignored) {}
            }
            if (classTempDir != null) deleteRecursively(classTempDir);
        }
        return new Result(passed, failed);
    }

    private static boolean needsTempDir(List<Method> methods) {
        for (Method m : methods) if (hasTempDirParam(m)) return true;
        return false;
    }

    private static boolean hasTempDirParam(Method m) {
        for (Parameter p : m.getParameters()) if (p.isAnnotationPresent(TempDir.class)) return true;
        return false;
    }

    private static Object[] resolveParams(Method m, Path tempDir) {
        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(TempDir.class) && params[i].getType().equals(Path.class)) {
                args[i] = tempDir;
            } else {
                throw new IllegalStateException("TestRunner cannot supply parameter " + params[i]
                    + " on " + m.getDeclaringClass().getSimpleName() + "." + m.getName());
            }
        }
        return args;
    }

    private static void invoke(Method m, Object instance, Object[] args) throws Exception {
        m.setAccessible(true);
        try {
            m.invoke(instance, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvocationTargetException && cur.getCause() != null) cur = cur.getCause();
        return cur;
    }

    private static List<Method> methodsAnnotatedWith(Class<?> testClass, Class<? extends Annotation> annotation, boolean mustBeStatic) {
        List<Method> found = new ArrayList<>();
        for (Method m : testClass.getDeclaredMethods()) {
            if (m.isAnnotationPresent(annotation) && Modifier.isStatic(m.getModifiers()) == mustBeStatic) found.add(m);
        }
        return found;
    }

    private static Path newTempDir() throws Exception {
        return Files.createTempDirectory("fb-test-");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best-effort cleanup only
        }
    }

    /** Scans the classpath's compiled com.fourbusiness(.**) test classes for *Test classes (excludes helpers like TestRunner itself). */
    private static List<Class<?>> discoverTestClasses() throws Exception {
        String cp = System.getProperty("java.class.path");
        List<Class<?>> result = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            File root = new File(entry);
            if (!root.isDirectory()) continue;
            collectClassFiles(root, root, result);
        }
        result.sort(Comparator.comparing(Class::getName));
        return result;
    }

    private static void collectClassFiles(File root, File dir, List<Class<?>> out) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { collectClassFiles(root, f, out); continue; }
            if (!f.getName().endsWith(".class") || f.getName().contains("$")) continue;
            String rel = root.toPath().relativize(f.toPath()).toString();
            String className = rel.substring(0, rel.length() - ".class".length()).replace(File.separatorChar, '.');
            if (!className.startsWith("com.fourbusiness")) continue;
            if (!className.endsWith("Test")) continue;
            if (className.equals("com.fourbusiness.TestRunner")) continue;
            Class<?> c = Class.forName(className);
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) continue;
            out.add(c);
        }
    }
}
