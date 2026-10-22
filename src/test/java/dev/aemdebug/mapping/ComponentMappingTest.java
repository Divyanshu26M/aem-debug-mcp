package dev.aemdebug.mapping;

import dev.aemdebug.aem.SlingModelRegistry;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectIndexer;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.util.Hashing;
import dev.aemdebug.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentMappingTest {

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    static final String EDIT_HTML = "<html><body><div class='root container responsivegrid'>"
            + "<div id='container-1' class='aem-Grid'>"
            + "<div class='hero aem-GridColumn'><h1></h1><cq data-path='/content/site/en/jcr:content/root/container/hero' "
            + "data-config='{&quot;type&quot;:&quot;site/components/hero&quot;,&quot;isContainer&quot;:false}'></cq></div>"
            + "<div class='teaser aem-GridColumn'><div id='teaser-0123456789' class='cmp-teaser'>T</div>"
            + "<cq data-path='/content/site/en/jcr:content/root/container/teaser' data-config='{}'></cq></div>"
            + "<!--cq{\"decorated\":true,\"type\":\"site/components/teaser\",\"path\":\"/content/site/en/jcr:content/root/container/teaser\","
            + "\"selectors\":null,\"servlet\":\"Script /apps/site/components/teaser/teaser.html\",\"totalTime\":3,\"selfTime\":2}-->"
            + "<div class='new section'></div>"
            + "<cq data-path='/content/site/en/jcr:content/root/container/*' data-config='{}'></cq>"
            + "</div></div></body></html>";

    @Test
    void hashesLikeCoreComponents() {
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String path = "/content/site/en/jcr:content/root/container/teaser";
        String id = CoreComponentIds.id("site/components/teaser", path);
        assertThat(id).isEqualTo("teaser-" + Hashing.sha256Hex(path).substring(0, 10));
        assertThat(CoreComponentIds.looksGenerated(id)).isTrue();
        assertThat(CoreComponentIds.looksGenerated("main-content")).isFalse();
    }

    @Test
    void parsesEditablesAndDebugComments() {
        StructureParser.Structure structure = StructureParser.parse(EDIT_HTML);
        assertThat(structure.editables).hasSize(2);
        StructureParser.Editable hero = structure.editables.get("/content/site/en/jcr:content/root/container/hero");
        assertThat(hero.resourceType).isEqualTo("site/components/hero");
        assertThat(hero.wrapperClasses).contains("hero");
        assertThat(hero.selector).isEqualTo("#container-1 > div:nth-of-type(1)");
        StructureParser.Editable teaser = structure.editables.get("/content/site/en/jcr:content/root/container/teaser");
        assertThat(teaser.selector).isEqualTo("#container-1 > div:nth-of-type(2)");
        StructureParser.DebugComment comment =
                structure.debugComments.get("/content/site/en/jcr:content/root/container/teaser");
        assertThat(comment).isNotNull();
        assertThat(comment.servlet).isEqualTo("Script /apps/site/components/teaser/teaser.html");
        assertThat(comment.totalTime).isEqualTo(3L);
        assertThat(comment.selector).isEqualTo("#container-1 > div:nth-of-type(2)");
    }

    @Test
    void flattensJcrAndBuildsProbeCandidates() {
        Object json = Json.parse("{\"jcr:primaryType\":\"cq:PageContent\",\"sling:resourceType\":\"site/components/page\","
                + "\"root\":{\"sling:resourceType\":\"site/components/container\",\"container\":{"
                + "\"sling:resourceType\":\"site/components/container\","
                + "\"hero\":{\"sling:resourceType\":\"site/components/hero\",\"jcr:title\":\"Welcome\"},"
                + "\"teaser\":{\"sling:resourceType\":\"site/components/teaser\",\"id\":\"custom-id\"},"
                + "\"cq:responsive\":{\"default\":{\"width\":\"12\"}}}}}");
        List<JcrComponents.Resource> resources = JcrComponents.flatten("/content/site/en/jcr:content", json, false);
        assertThat(resources).extracting(r -> r.path).containsExactly("/content/site/en/jcr:content",
                "/content/site/en/jcr:content/root", "/content/site/en/jcr:content/root/container",
                "/content/site/en/jcr:content/root/container/hero", "/content/site/en/jcr:content/root/container/teaser");
        assertThat(resources.get(3).properties).containsEntry("jcr:title", "Welcome");

        List<ComponentNode> nodes = new ArrayList<>();
        for (JcrComponents.Resource resource : resources.subList(1, resources.size())) {
            ComponentNode node = new ComponentNode();
            node.path = resource.path;
            node.resourceType = resource.resourceType;
            node.depth = resource.depth;
            node.coreId = resource.properties.get("id") != null ? (String) resource.properties.get("id")
                    : CoreComponentIds.id(resource.resourceType, resource.path);
            nodes.add(node);
        }
        List<Map<String, Object>> candidates = ComponentMapper.candidates(nodes);
        assertThat(candidates).hasSize(4);
        assertThat(candidates.get(3)).containsEntry("coreId", "custom-id").containsEntry("cls", "teaser");

        Map<String, Object> probe = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("/content/site/en/jcr:content/root/container/hero", map("found", true, "how", "edit-markup",
                "x", 0, "y", 300.5, "w", 1440, "h", 0, "textLength", 0, "images", 0, "visible", false, "id", "x"));
        components.put("/content/site/en/jcr:content/root/container/teaser", map("found", true, "how", "core-id",
                "x", 10, "y", 100, "w", 400, "h", 200, "textLength", 12, "text", "Teaser title", "images", 1,
                "visible", true, "id", "custom-id"));
        probe.put("components", components);
        probe.put("dataLayer", Map.of("custom-id", "site/components/teaser"));
        ComponentMapper.applyProbe(nodes, probe);
        ComponentMapper.number(nodes);
        ComponentNode teaser = nodes.get(0);
        assertThat(teaser.path).endsWith("/teaser");
        assertThat(teaser.number).isEqualTo(1);
        assertThat(teaser.dataLayerType).isEqualTo("site/components/teaser");
        assertThat(teaser.renderedEmpty()).isFalse();
        ComponentNode hero = nodes.get(1);
        assertThat(hero.path).endsWith("/hero");
        assertThat(hero.renderedEmpty()).isTrue();
        assertThat(Hints.renderHint(hero)).contains("not visible");
        assertThat(nodes.get(2).found).isFalse();
        assertThat(Hints.renderHint(nodes.get(2))).contains("not located");
        assertThat(nodes.get(2).shortPath("/content/site/en")).isEqualTo("root");
    }

    @Test
    void enrichesWithRuntimeAndLocalModels() throws Exception {
        ProjectIndex index = ProjectIndexer.index(Paths.get(getClass().getResource("/sample-project").toURI()));
        SlingModelRegistry registry = SlingModelRegistry.parse("Sling Models Bound to Resource Types *For Requests*:\n"
                + "com.adobe.cq.wcm.core.components.internal.models.v2.TeaserImpl - core/wcm/components/teaser/v2/teaser\n"
                + "com.mysite.core.models.HeroModel - mysite/components/hero\n");
        ComponentNode node = new ComponentNode();
        node.path = "/content/site/en/jcr:content/root/hero";
        node.resourceType = "mysite/components/hero";
        ComponentMapper.enrichNode(node, registry, index);
        assertThat(node.runtimeModels).containsExactly("com.mysite.core.models.HeroModel",
                "com.adobe.cq.wcm.core.components.internal.models.v2.TeaserImpl");
        assertThat(node.localModels).containsExactly("com.mysite.core.models.HeroModel");
        assertThat(node.localSources.get(0)).endsWith("HeroModel.java");
        assertThat(node.localScripts.get(0)).endsWith("hero.html");

        ProjectModel.SlingModel model = index.modelByClass("HeroModel");
        Map<String, Object> jcr = new LinkedHashMap<>();
        jcr.put("jcr:title", "Welcome");
        jcr.put("sling:resourceType", "mysite/components/hero");
        List<String> hints = Hints.modelVsContent(model, jcr);
        assertThat(hints).anyMatch(h -> h.contains("JCR has 'jcr:title' but field 'title' injects 'title'")
                && h.contains("HeroModel.java:"));
        Map<String, Object> modelJson = new LinkedHashMap<>();
        modelJson.put("title", null);
        assertThat(Hints.modelJsonVsContent(modelJson, jcr)).anyMatch(h -> h.contains("model JSON title=null"));
    }
}
