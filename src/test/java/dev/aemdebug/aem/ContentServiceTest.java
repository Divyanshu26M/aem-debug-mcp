package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentServiceTest {

    @Test
    void encodesPathsKeepingColonsAndSlashes() {
        assertThat(ContentService.encodePath("/content/my site/jcr:content")).isEqualTo("/content/my%20site/jcr:content");
        assertThat(ContentService.encodePath("/content/ü")).isEqualTo("/content/%C3%BC");
        assertThat(ContentService.encodePath("/content/a#b?c")).isEqualTo("/content/a%23b%3Fc");
    }

    @Test
    void normalizesAndRejectsTraversal() {
        assertThat(ContentService.normalizePath("content/site/")).isEqualTo("/content/site");
        assertThatThrownBy(() -> ContentService.normalizePath("/content/../etc/passwd"))
                .hasMessageContaining("Relative path segments");
    }

    @Test
    void derivesPagePathAndLanguage() {
        assertThat(ContentService.pagePathOf("/content/site/en.html?wcmmode=disabled")).isEqualTo("/content/site/en");
        assertThat(ContentService.pagePathOf("/content/site/en/jcr:content/root/hero")).isEqualTo("/content/site/en");
        assertThat(ContentService.languageFromPath("/content/site/us/en")).isEqualTo("en");
        assertThat(ContentService.languageFromPath("/content/site/language-masters/fr_CA")).isEqualTo("fr_CA");
        assertThat(ContentService.languageFromPath("/content/site/home")).isNull();
    }

    @Test
    void findsPoliciesInTemplateMapping() {
        Map<String, Object> mapping = Json.parseObject("{\"cq:policy\":\"site/components/page/policy_page\","
                + "\"root\":{\"cq:policy\":\"site/components/container/policy_root\",\"container\":{"
                + "\"cq:policy\":\"site/components/container/policy_main\",\"site\":{\"components\":{\"teaser\":"
                + "{\"cq:policy\":\"site/components/teaser/policy_teaser\"}}}}}}");
        assertThat(ContentService.policyAt(mapping, "root")).isEqualTo("site/components/container/policy_root");
        assertThat(ContentService.policyAt(mapping, "root/container/site/components/teaser"))
                .isEqualTo("site/components/teaser/policy_teaser");
        assertThat(ContentService.policyAt(mapping, "root/container/teaser_123")).isNull();
    }

    @Test
    void rendersResourceTreesWithMasking() {
        Object json = Json.parse("{\"jcr:primaryType\":\"cq:PageContent\",\"sling:resourceType\":\"site/components/page\","
                + "\"jcr:title\":\"English\",\"apiKey\":\"abc\",\"root\":{\"jcr:primaryType\":\"nt:unstructured\","
                + "\"tags\":[\"a\",\"b\"]}}");
        String tree = ResourceTree.render("/content/site/en/jcr:content", json, 50, null);
        assertThat(tree).contains("/content/site/en/jcr:content  [cq:PageContent, site/components/page]")
                .contains("jcr:title = \"English\"").contains("apiKey = \"****\"").contains("  root/  [nt:unstructured]")
                .contains("tags = [\"a\", \"b\"]");
        String filtered = ResourceTree.render("/x", json, 50, java.util.List.of("jcr:*"));
        assertThat(filtered).contains("jcr:title").doesNotContain("apiKey");
    }
}
