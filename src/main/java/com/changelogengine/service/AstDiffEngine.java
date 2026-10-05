package com.changelogengine.service;

import com.changelogengine.dto.BreakingChangeDto;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Service
public class AstDiffEngine {

    private static final Logger log = LoggerFactory.getLogger(AstDiffEngine.class);

    /**
     * Compares two JAR files (old vs new version) using JavaParser AST inspection.
     * Identifies removed public classes, interfaces, and methods.
     */
    public List<BreakingChangeDto> compareJars(File oldJarFile, File newJarFile) {
        List<BreakingChangeDto> breakingChanges = new ArrayList<>();

        Map<String, CompilationUnit> oldAstMap = parseJarToAst(oldJarFile);
        Map<String, CompilationUnit> newAstMap = parseJarToAst(newJarFile);

        log.info("Analyzing AST nodes: Old JAR contains {} source nodes, New JAR contains {} source nodes",
                oldAstMap.size(), newAstMap.size());

        for (Map.Entry<String, CompilationUnit> entry : oldAstMap.entrySet()) {
            String className = entry.getKey();
            CompilationUnit oldCu = entry.getValue();

            // Check if class was completely removed in the new JAR
            if (!newAstMap.containsKey(className)) {
                breakingChanges.add(new BreakingChangeDto(
                        className,
                        "N/A",
                        "Public class or interface was completely removed in target version.",
                        "HIGH"
                ));
                continue;
            }

            CompilationUnit newCu = newAstMap.get(className);
            compareClassMethods(className, oldCu, newCu, breakingChanges);
        }

        return breakingChanges;
    }

    private Map<String, CompilationUnit> parseJarToAst(File jarFile) {
        Map<String, CompilationUnit> astMap = new HashMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                // JavaParser inspects Java source files (.java)
                if (entry.getName().endsWith(".java") && !entry.isDirectory()) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        CompilationUnit cu = StaticJavaParser.parse(is);
                        String className = entry.getName().replace('/', '.').replace(".java", "");
                        astMap.put(className, cu);
                    } catch (Exception e) {
                        log.warn("Could not parse AST node for file: {}", entry.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error reading JAR file for AST analysis: {}", jarFile.getName(), e);
        }

        return astMap;
    }

    private void compareClassMethods(String className, CompilationUnit oldCu, CompilationUnit newCu, List<BreakingChangeDto> breakingChanges) {
        Optional<ClassOrInterfaceDeclaration> oldClass = oldCu.getClassByName(getSimpleClassName(className));
        Optional<ClassOrInterfaceDeclaration> newClass = newCu.getClassByName(getSimpleClassName(className));

        if (oldClass.isPresent() && newClass.isPresent()) {
            List<MethodDeclaration> oldMethods = oldClass.get().getMethods();
            List<MethodDeclaration> newMethods = newClass.get().getMethods();

            Set<String> newMethodSignatures = new HashSet<>();
            for (MethodDeclaration method : newMethods) {
                if (method.isPublic()) {
                    newMethodSignatures.add(method.getSignature().asString());
                }
            }

            for (MethodDeclaration oldMethod : oldMethods) {
                if (oldMethod.isPublic()) {
                    String signature = oldMethod.getSignature().asString();
                    if (!newMethodSignatures.contains(signature)) {
                        breakingChanges.add(new BreakingChangeDto(
                                className,
                                signature,
                                "Public method signature was modified or removed.",
                                "HIGH"
                        ));
                    }
                }
            }
        }
    }

    private String getSimpleClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot != -1 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }
}