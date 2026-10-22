package dev.aemdebug.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexerTest {

    static Path sample() throws Exception {
        return Paths.get(ProjectIndexerTest.class.getResource("/sample-project").toURI());
    }

    @Test
    void indexesModelsWithResolvedResourceTypesAndInjectedFields() throws Exception {
        ProjectIndex index = ProjectIndexer.index(sample());
        assertThat(index.classes).hasSize(3);
        assertThat(index.models).hasSize(1);
        ProjectModel.SlingModel model = index.models.get(0);
        assertThat(model.className).isEqualTo("com.mysite.core.models.HeroModel");
        assertThat(model.resourceTypes).containsExactly("mysite/components/hero");
        assertThat(model.adaptables).containsExactly("SlingHttpServletRequest", "Resource");
        assertThat(model.adapters).containsExactly("Hero");
        assertThat(model.exporter).isTrue();
        assertThat(model.optionalByDefault).isFalse();
        assertThat(model.postConstructMethod).isEqualTo("init");
        assertThat(model.fields).extracting(f -> f.name).containsExactly("title", "description", "items", "settings");
        ProjectModel.InjectedField title = model.fields.get(0);
        assertThat(title.annotation).isEqualTo("ValueMapValue");
        assertThat(title.lookupName()).isEqualTo("title");
        assertThat(title.optional).isFalse();
        List<String> lines = Files.readAllLines(model.file);
        assertThat(lines.get(title.line - 1)).contains("private String title;");
        ProjectModel.InjectedField description = model.fields.get(1);
        assertThat(description.nameOverride).isEqualTo("jcr:description");
        assertThat(description.optional).isTrue();
        assertThat(model.fields.get(2).type).isEqualTo("List<Resource>");
        assertThat(model.fields.get(3).type).isEqualTo("Map<String, Object>");
        assertThat(index.modelsForResourceType("mysite/components/hero")).hasSize(1);
        assertThat(index.modelsWithAdapter("Hero")).hasSize(1);
        assertThat(index.classesForSourceFile("core/src/main/java/com/mysite/core/models/HeroModel.java")).hasSize(1);
        assertThat(index.projectPackages()).containsExactly("com.mysite");
    }

    @Test
    void indexesComponentsScriptsServletsConfigsAndClientlibs() throws Exception {
        ProjectIndex index = ProjectIndexer.index(sample());
        ProjectModel.Component hero = index.component("mysite/components/hero");
        assertThat(hero).isNotNull();
        assertThat(hero.title).isEqualTo("Hero");
        assertThat(hero.superType).isEqualTo("core/wcm/components/teaser/v2/teaser");
        assertThat(hero.hasDialog).isTrue();
        assertThat(hero.scripts).hasSize(1);
        ProjectModel.HtlScript script = index.scriptByJcrPath("/apps/mysite/components/hero/hero.html");
        assertThat(script).isNotNull();
        assertThat(script.useObjects).containsEntry("hero", "com.mysite.core.models.HeroModel")
                .containsEntry("helper", "com.mysite.core.models.HeroHelper")
                .containsEntry("template", "core/wcm/components/commons/v1/templates.html");

        assertThat(index.servlets).hasSize(2);
        ProjectModel.Servlet data = index.servlets.stream()
                .filter(s -> s.className.endsWith("PageDataServlet")).findFirst().orElseThrow();
        assertThat(data.resourceTypes).containsExactly("mysite/components/page");
        assertThat(data.selectors).containsExactly("data");
        assertThat(data.extensions).containsExactly("json");
        ProjectModel.Servlet search = index.servlets.stream()
                .filter(s -> s.className.endsWith("SearchServlet")).findFirst().orElseThrow();
        assertThat(search.paths).containsExactly("/bin/mysite/search");
        assertThat(search.methods).containsExactly("POST");

        assertThat(index.osgiConfigs).hasSize(2);
        List<ProjectModel.OsgiConfigFile> logConfigs =
                index.osgiConfigsFor("org.apache.sling.commons.log.LogManager.factory.config");
        assertThat(logConfigs).hasSize(1);
        assertThat(logConfigs.get(0).runModes).isEqualTo("author");
        assertThat(logConfigs.get(0).name).endsWith("~mysite");
        assertThat(index.osgiConfigsFor("com.day.cq.wcm.core.impl.WCMDebugFilter").get(0).runModes).isEqualTo("all");

        assertThat(index.clientLibs).hasSize(1);
        assertThat(index.clientLibs.get(0).categories).containsExactly("mysite.site", "mysite.hero");
        assertThat(index.clientLibs.get(0).allowProxy).isFalse();
        assertThat(index.clientLibsForCategory("mysite.hero")).hasSize(1);

        ProjectModel.Module core = index.modules.stream()
                .filter(m -> "mysite.core".equals(m.artifactId)).findFirst().orElseThrow();
        assertThat(core.groupId).isEqualTo("com.mysite");
        assertThat(core.bundleSymbolicName).isEqualTo("com.mysite.core");
        assertThat(index.summary()).contains("1 Sling Models").contains("2 servlets");
        assertThat(index.warnings).anyMatch(w -> w.contains("data-sly-use.helper=\"com.mysite.core.models.HeroHelper\""));
        assertThat(index.warnings).noneMatch(w -> w.contains("HeroModel\""));
    }

    @Test
    void helpersParseNamesAndComments() {
        assertThat(ProjectIndexer.decodeName("_jcr_content")).isEqualTo("jcr:content");
        assertThat(ProjectIndexer.decodeName("_cq_dialog")).isEqualTo("cq:dialog");
        assertThat(ProjectIndexer.decodeName("hero")).isEqualTo("hero");
        String stripped = ProjectIndexer.stripComments("a /* x\ny */ b // c\n\"// not a comment\"");
        String[] strippedLines = stripped.split("\n", -1);
        assertThat(strippedLines).hasSize(3);
        assertThat(strippedLines[0].trim()).isEqualTo("a");
        assertThat(strippedLines[1].trim()).isEqualTo("b");
        assertThat(strippedLines[2]).isEqualTo("\"// not a comment\"");
        assertThat(ProjectIndexer.attribute("resourceType = {\"a\", \"b\"}, x = 1", "resourceType"))
                .isEqualTo("{\"a\", \"b\"}");
        assertThat(ProjectIndexer.stringsOrConstants("{\"a\", \"b\"}", "")).containsExactly("a", "b");
        assertThat(ProjectIndexer.stringsOrConstants("Constants.TYPE", "String TYPE = \"x/y\";"))
                .containsExactly("x/y");
    }

    @Test
    void locatesAemProjectRoots() throws Exception {
        Path sample = sample();
        assertThat(ProjectIndexService.looksLikeAemProject(sample)).isTrue();
        assertThat(ProjectIndexService.findProjectRoot(sample.resolve("core").resolve("src"))).isEqualTo(sample);
    }

    @Test
    void readsLineTablesOfCompiledClasses() throws Exception {
        Path classFile = Paths.get(ProjectIndexerTest.class
                .getResource("/dev/aemdebug/fixture/models/HeroModel.class").toURI());
        ClassFileInfo info = ClassFileInfo.read(classFile);
        assertThat(info.className).isEqualTo("dev.aemdebug.fixture.models.HeroModel");
        assertThat(info.sourceFile).isEqualTo("HeroModel.java");
        ClassFileInfo.MethodLines getTitle = info.methods.get("getTitle()Ljava/lang/String;");
        assertThat(getTitle).isNotNull();
        int marker = markerLine("dev/aemdebug/fixture/models/HeroModel.java", "BP:hero-title");
        assertThat(getTitle.lines).contains(marker);
        assertThat(info.allLines).contains(marker);
    }

    static int markerLine(String relativeSource, String marker) throws Exception {
        String root = System.getProperty("aemdebug.fixtureSources", "fixture-app/src/main/java");
        List<String> lines = Files.readAllLines(Paths.get(root).resolve(relativeSource));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("marker " + marker + " not found");
    }
}
