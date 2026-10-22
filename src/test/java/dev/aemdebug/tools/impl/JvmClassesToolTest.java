package dev.aemdebug.tools.impl;

import dev.aemdebug.fixture.models.HeroModel;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.project.ClassFileInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class JvmClassesToolTest {

    @Test
    void comparesDeployedLineTablesWithTheLocalBuild() throws Exception {
        Path classFile = Paths.get(HeroModel.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("dev/aemdebug/fixture/models/HeroModel.class");
        ClassFileInfo local = ClassFileInfo.read(classFile);

        JdiEngine.ClassVersion same = new JdiEngine.ClassVersion();
        same.name = HeroModel.class.getName();
        for (ClassFileInfo.MethodLines method : local.methods.values()) {
            if (!method.lines.isEmpty()) {
                same.methodLines.put(method.name + method.descriptor, new int[] {method.first(), method.last()});
            }
        }
        assertThat(same.methodLines).containsKeys("getTitle()Ljava/lang/String;", "getSubtitle()Ljava/lang/String;");
        assertThat(JvmClassesTool.differences(same, local)).isEmpty();

        JdiEngine.ClassVersion stale = new JdiEngine.ClassVersion();
        stale.name = same.name;
        stale.methodLines.putAll(same.methodLines);
        stale.methodLines.put("getTitle()Ljava/lang/String;", new int[] {1, 2});
        stale.methodLines.remove("getSubtitle()Ljava/lang/String;");
        stale.methodLines.put("oldMethod()V", new int[] {50, 52});
        assertThat(JvmClassesTool.differences(stale, local)).hasSize(3)
                .anySatisfy(d -> assertThat(d).startsWith("getTitle()Ljava/lang/String;: AEM lines 1-2, local "))
                .anySatisfy(d -> assertThat(d).startsWith("getSubtitle()Ljava/lang/String; only in the local build"))
                .anySatisfy(d -> assertThat(d).isEqualTo("oldMethod()V only in AEM (lines 50-52)"));
    }
}
