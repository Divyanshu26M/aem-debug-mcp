package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FelixConsoleParsersTest {

    @Test
    void parsesBundlesJson() {
        String json = "{\"status\":\"Bundle information: 3 bundles in total\",\"s\":[3,1,1,1,0],\"data\":["
                + "{\"id\":0,\"name\":\"System Bundle\",\"fragment\":false,\"stateRaw\":32,\"state\":\"Active\","
                + "\"version\":\"7.0.5\",\"symbolicName\":\"org.apache.felix.framework\",\"category\":\"\"},"
                + "{\"id\":412,\"name\":\"My Site Core\",\"fragment\":false,\"stateRaw\":2,\"state\":\"Installed\","
                + "\"version\":\"1.0.0.SNAPSHOT\",\"symbolicName\":\"com.mysite.core\",\"category\":\"\"},"
                + "{\"id\":7,\"name\":\"Frag\",\"fragment\":true,\"stateRaw\":4,\"state\":\"Fragment\","
                + "\"version\":\"1.0\",\"symbolicName\":\"frag\",\"category\":\"\"}]}";
        List<BundleInfo> bundles = FelixConsole.parseBundles(Json.parseObject(json));
        assertThat(bundles).hasSize(3);
        assertThat(bundles.get(0).healthy()).isTrue();
        assertThat(bundles.get(1).healthy()).isFalse();
        assertThat(bundles.get(1).symbolicName).isEqualTo("com.mysite.core");
        assertThat(bundles.get(1).id).isEqualTo(412);
        assertThat(bundles.get(2).healthy()).isTrue();
    }

    @Test
    void parsesBundleDetailProps() {
        String json = "{\"data\":[{\"id\":412,\"symbolicName\":\"com.mysite.core\",\"props\":["
                + "{\"key\":\"Last Modification\",\"value\":\"Wed Oct 22 10:00:00 IST 2026\"},"
                + "{\"key\":\"Exported Packages\",\"value\":[\"com.mysite.core.models,version=1.0\",\"b\"]}]}]}";
        Map<String, String> detail = FelixConsole.parseBundleDetail(Json.parseObject(json));
        assertThat(detail).containsEntry("Last Modification", "Wed Oct 22 10:00:00 IST 2026");
        assertThat(detail.get("Exported Packages")).contains("com.mysite.core.models");
        assertThat(detail).containsEntry("symbolicName", "com.mysite.core");
    }

    @Test
    void parsesConfigMgrJsonArray() {
        String json = "[{\"pid\":\"com.day.cq.wcm.core.impl.WCMDebugFilter\",\"title\":\"Day CQ WCM Debug Filter\","
                + "\"properties\":{\"wcmdbgfilter.enabled\":{\"name\":\"Enable\",\"optional\":false,\"is_set\":true,"
                + "\"type\":11,\"value\":false},\"wcmdbgfilter.jspDebug\":{\"name\":\"JSP\",\"is_set\":false,"
                + "\"type\":11,\"value\":false},\"multi\":{\"type\":1,\"values\":[\"a\",\"b\"]}}}]";
        List<OsgiConfiguration> configs = FelixConsole.parseConfigMgrJson(Json.parse(json));
        assertThat(configs).hasSize(1);
        OsgiConfiguration config = configs.get(0);
        assertThat(config.pid).isEqualTo("com.day.cq.wcm.core.impl.WCMDebugFilter");
        assertThat(config.properties).containsEntry("wcmdbgfilter.enabled", false);
        assertThat(config.properties).doesNotContainKey("wcmdbgfilter.jspDebug");
        assertThat(config.properties.get("multi")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void parsesConfigurationsStatusText() {
        String text = "*** Configurations:\n\n"
                + "PID = org.apache.sling.commons.log.LogManager.factory.config.1234\n"
                + "  BundleLocation = slinginstall:org.apache.sling.commons.log\n"
                + "  Factory PID = org.apache.sling.commons.log.LogManager.factory.config\n"
                + "  org.apache.sling.commons.log.level = debug\n"
                + "  org.apache.sling.commons.log.names = [com.mysite]\n"
                + "\n"
                + "PID = com.day.cq.wcm.core.impl.WCMDebugFilter\n"
                + "  wcmdbgfilter.enabled = true\n";
        List<OsgiConfiguration> configs = FelixConsole.parseConfigurationsText(text);
        assertThat(configs).hasSize(2);
        assertThat(configs.get(0).factoryPid).isEqualTo("org.apache.sling.commons.log.LogManager.factory.config");
        assertThat(configs.get(0).properties).containsEntry("org.apache.sling.commons.log.level", "debug");
        assertThat(configs.get(0).bundleLocation).startsWith("slinginstall:");
        assertThat(configs.get(1).properties).containsEntry("wcmdbgfilter.enabled", "true");
    }

    @Test
    void parsesSlingSettings() {
        String text = "*** Sling Settings:\n\nSling ID = 0a1b\nSling Name = Instance 0a1b\n"
                + "Sling Home = C:\\aem\\author\\crx-quickstart\nSling Home URL = file:/C:/aem/author/crx-quickstart/\n"
                + "Run Modes = [crx3, author, samplecontent, crx3tar]\n";
        SlingSettings settings = SlingSettings.parse(text);
        assertThat(settings.slingId).isEqualTo("0a1b");
        assertThat(settings.slingHome).isEqualTo("C:\\aem\\author\\crx-quickstart");
        assertThat(settings.runModes).containsExactly("crx3", "author", "samplecontent", "crx3tar");
    }

    @Test
    void parsesSlingModelsPrinter() {
        String text = "Sling Models Injectors:\n"
                + "org.apache.sling.models.impl.injectors.ValueMapInjector - valuemap\n"
                + "Sling Models Bound to Resource Types *For Resources*:\n"
                + "com.mysite.core.models.HeroModel - mysite/components/hero\n"
                + "Sling Models Bound to Resource Types *For Requests*:\n"
                + "com.adobe.cq.wcm.core.components.internal.models.v2.TeaserImpl - core/wcm/components/teaser/v2/teaser\n"
                + "com.mysite.core.models.HeroRequestModel - mysite/components/hero\n";
        SlingModelRegistry registry = SlingModelRegistry.parse(text);
        assertThat(registry.injectorCount).isEqualTo(1);
        assertThat(registry.modelsFor("mysite/components/hero"))
                .containsExactly("com.mysite.core.models.HeroRequestModel", "com.mysite.core.models.HeroModel");
        assertThat(registry.resourceTypesFor("TeaserImpl")).containsExactly("core/wcm/components/teaser/v2/teaser");
        assertThat(registry.bindingCount()).isEqualTo(3);
    }

    @Test
    void parsesSystemOverviewAndProductInfo() {
        Map<String, Object> overview = Json.parseObject(
                "{\"Instance\":{\"Adobe Experience Manager\":\"2025.10.22994.20251001T120000Z\",\"Run Modes\":\"author\"}}");
        assertThat(FelixConsole.parseSystemOverviewVersion(overview))
                .isEqualTo("Adobe Experience Manager 2025.10.22994.20251001T120000Z");
        assertThat(FelixConsole.parseProductInfo("Product Information\n\nAdobe Experience Manager\n  Version: 6.5.21.0\n"))
                .isEqualTo("Adobe Experience Manager 6.5.21.0");
    }
}
