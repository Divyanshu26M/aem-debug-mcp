package dev.aemdebug.project;

import dev.aemdebug.aem.BundleInfo;
import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectIndexServiceTest {

    @TempDir
    Path temp;

    private Path repo;
    private Path other;
    private Path plain;
    private Path nothing;
    private Config config;

    @BeforeEach
    void createProjects() throws IOException {
        repo = temp.resolve("multi-site");
        pom(repo, "com.example", "digital", "pom", "site-a", "site-b");
        aemProject(repo.resolve("site-a"), "com.example", "site-a", "com.a", "AModel");
        aemProject(repo.resolve("site-b"), "com.example", "site-b", "com.b", "BModel");
        other = temp.resolve("other-repo");
        aemProject(other, "com.other", "other", "com.o", "OModel");
        plain = temp.resolve("plain-lib");
        pom(plain, "com.plain", "plain", "jar");
        nothing = Files.createDirectories(temp.resolve("not-a-project"));
        config = Config.builder().artifactsDir(temp.resolve("artifacts")).build();
    }

    @Test
    void detectsAggregatorsOfAemProjectsFromAnyFolderInside() {
        assertThat(ProjectIndexService.detectRoot(repo, false)).isEqualTo(repo);
        assertThat(ProjectIndexService.detectRoot(repo.resolve("site-a"), false)).isEqualTo(repo);
        assertThat(ProjectIndexService.detectRoot(repo.resolve("site-b/core/src/main/java/com/b"), false))
                .isEqualTo(repo);
        assertThat(ProjectIndexService.detectRoot(other.resolve("ui.apps"), false)).isEqualTo(other);
        assertThat(ProjectIndexService.detectRoot(plain, false)).isNull();
        assertThat(ProjectIndexService.detectRoot(plain, true)).isEqualTo(plain);
        assertThat(ProjectIndexService.detectRoot(nothing, true)).isNull();
        assertThat(ProjectIndexService.detectRoot(temp.resolve("missing"), true)).isNull();
    }

    @Test
    void asksForAProjectWhenNoneIsKnown() {
        ProjectIndexService service = new ProjectIndexService(config);
        assertThat(service.environment()).isEqualTo("http://localhost:4502");
        assertThat(service.useWorkspace(nothing)).isNull();
        assertThat(service.roots(ToolContext.detached())).isEmpty();
        assertThatThrownBy(() -> service.require(ToolContext.detached()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("http://localhost:4502")
                .hasMessageContaining("project_index projectDir=<folder>");
    }

    @Test
    void workspaceIsUsedForTheChatOnlyAndNamedProjectsAreRemembered() {
        ProjectIndexService chat = new ProjectIndexService(config);
        assertThat(chat.useWorkspace(repo.resolve("site-a/core"))).isEqualTo(repo);
        assertThat(chat.roots(ToolContext.detached()))
                .containsExactly(new ProjectIndexService.Root(repo, "this chat's folder"));
        ProjectIndex index = chat.get(ToolContext.detached());
        assertThat(index.root).isEqualTo(repo);
        assertThat(index.classByName("com.a.AModel")).isNotNull();
        assertThat(index.classByName("com.b.BModel")).isNotNull();
        assertThat(chat.memoryFile()).doesNotExist();

        assertThat(chat.addProject(other.resolve("core"), false)).isEqualTo(other);
        assertThat(chat.memoryFile()).exists();
        assertThat(chat.roots(ToolContext.detached())).extracting(ProjectIndexService.Root::path)
                .containsExactly(repo, other);
        ProjectIndex merged = chat.get(ToolContext.detached());
        assertThat(merged.root).isEqualTo(temp);
        assertThat(merged.classByName("com.o.OModel")).isNotNull();
        assertThat(merged.classByName("com.a.AModel")).isNotNull();
        assertThat(merged.relative(merged.classByName("com.o.OModel").file))
                .isEqualTo("other-repo/core/src/main/java/com/o/OModel.java");

        ProjectIndexService nextChat = new ProjectIndexService(config);
        assertThat(nextChat.roots(ToolContext.detached()))
                .containsExactly(new ProjectIndexService.Root(other, "remembered for http://localhost:4502"));

        assertThat(nextChat.addProject(repo.resolve("site-b"), false)).isEqualTo(repo);
        assertThat(new ProjectIndexService(config).roots(ToolContext.detached()))
                .extracting(ProjectIndexService.Root::path).containsExactly(repo, other);
    }

    @Test
    void replacesAndForgetsNamedProjects() {
        ProjectIndexService chat = new ProjectIndexService(config);
        chat.addProject(repo, false);
        chat.addProject(other, false);
        assertThat(chat.addProject(plain, true)).isEqualTo(plain);
        assertThat(chat.roots(ToolContext.detached())).extracting(ProjectIndexService.Root::path)
                .containsExactly(plain);
        assertThat(new ProjectIndexService(config).roots(ToolContext.detached()))
                .extracting(ProjectIndexService.Root::path).containsExactly(plain);

        assertThat(chat.removeProject(plain)).isTrue();
        assertThat(chat.removeProject(plain)).isFalse();
        assertThat(chat.roots(ToolContext.detached())).isEmpty();
        assertThat(new ProjectIndexService(config).roots(ToolContext.detached())).isEmpty();

        assertThatThrownBy(() -> chat.addProject(nothing, false)).isInstanceOf(ToolException.class)
                .hasMessageContaining("not inside a Maven project");
        assertThatThrownBy(() -> chat.addProject(temp.resolve("missing"), false)).isInstanceOf(ToolException.class);
    }

    @Test
    void projectsAreRememberedPerAemInstance() {
        new ProjectIndexService(config).addProject(other, false);
        Config elsewhere = Config.builder().authorUrl("http://localhost:6502").artifactsDir(temp.resolve("artifacts"))
                .build();
        assertThat(new ProjectIndexService(elsewhere).roots(ToolContext.detached())).isEmpty();
        assertThat(new ProjectIndexService(config).roots(ToolContext.detached())).hasSize(1);
    }

    @Test
    void reportsWhichProjectsAreDeployed() throws IOException {
        Path content = temp.resolve("content-only");
        pom(content, "com.content", "content", "pom");
        Files.createDirectories(content.resolve("ui.apps"));
        List<ProjectIndexService.Root> roots = List.of(new ProjectIndexService.Root(repo, "test"),
                new ProjectIndexService.Root(other, "test"), new ProjectIndexService.Root(content, "test"));
        ProjectIndex index = ProjectIndex.merge(List.of(ProjectIndexer.index(repo), ProjectIndexer.index(other),
                ProjectIndexer.index(content)));

        List<String> lines = ProjectMatch.describe(index, roots, List.of(
                bundle("site-a.core"), bundle("com.example.site-b.core"), bundle("org.apache.sling.engine"),
                bundle("com.other.othertool")));

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("multi-site: deployed (")
                .contains("site-a.core Active").contains("com.example.site-b.core Active");
        assertThat(lines.get(1)).startsWith("other-repo: NOT deployed here - none of its bundles is installed "
                + "(expected other.core); similar installed bundles: com.other.othertool");
        assertThat(lines.get(2)).isEqualTo("content-only: builds no OSGi bundles, not checked");

        List<String> partly = ProjectMatch.describe(index, roots.subList(0, 1), List.of(bundle("site-a.core")));
        assertThat(partly).containsExactly(
                "multi-site: partly deployed - installed site-a.core Active; not installed site-b.core");
    }

    @Test
    void resolvesBundleNamePlaceholders() {
        ProjectModel.Module module = new ProjectModel.Module();
        module.dir = repo;
        module.groupId = "com.x";
        module.artifactId = "x.core";
        module.bundleSymbolicName = "${project.groupId}.${project.artifactId}";
        assertThat(ProjectMatch.expectedBundleNames(module)).containsExactly("com.x.x.core");
    }

    private static BundleInfo bundle(String symbolicName) {
        return new BundleInfo(1, symbolicName, symbolicName, "1.0.0", "Active", 32, false);
    }

    /** An archetype-like AEM project: core bundle with one class, ui.apps and ui.config. */
    private static void aemProject(Path dir, String groupId, String name, String pkg, String className)
            throws IOException {
        pom(dir, groupId, name, "pom", "core", "ui.apps", "ui.config");
        Path core = dir.resolve("core");
        Files.createDirectories(core);
        write(core.resolve("pom.xml"), "<project>\n  <groupId>" + groupId + "</groupId>\n  <artifactId>" + name
                + ".core</artifactId>\n  <build><plugins><plugin><artifactId>bnd-maven-plugin</artifactId></plugin>"
                + "</plugins></build>\n</project>\n");
        Path source = core.resolve("src/main/java/" + pkg.replace('.', '/'));
        Files.createDirectories(source);
        write(source.resolve(className + ".java"), "package " + pkg + ";\n\npublic class " + className + " {\n}\n");
        pom(dir.resolve("ui.apps"), groupId, name + ".ui.apps", "content-package");
        pom(dir.resolve("ui.config"), groupId, name + ".ui.config", "content-package");
    }

    private static void pom(Path dir, String groupId, String artifactId, String packaging, String... modules)
            throws IOException {
        Files.createDirectories(dir);
        StringBuilder xml = new StringBuilder("<project>\n  <groupId>").append(groupId).append("</groupId>\n")
                .append("  <artifactId>").append(artifactId).append("</artifactId>\n")
                .append("  <packaging>").append(packaging).append("</packaging>\n");
        if (modules.length > 0) {
            xml.append("  <modules>\n");
            for (String module : modules) {
                xml.append("    <module>").append(module).append("</module>\n");
            }
            xml.append("    <!-- <module>disabled</module> -->\n  </modules>\n");
        }
        write(dir.resolve("pom.xml"), xml.append("</project>\n").toString());
    }

    private static void write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
