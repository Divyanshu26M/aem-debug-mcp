package dev.aemdebug.fixture;

import dev.aemdebug.fixture.models.ErrorModel;
import dev.aemdebug.fixture.models.HeroModel;
import dev.aemdebug.fixture.models.LoopModel;
import org.apache.sling.api.resource.Resource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Page "rendering" for the fixture paths. */
public final class FixturePages {

    private FixturePages() {
    }

    public static String render(String path) throws Exception {
        switch (path) {
            case "/ping":
                return "pong";
            case "/content/fixture/hero.html": {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("jcr:title", "Welcome");
                props.put("subtitle", "Hello there");
                props.put("password", "s3cret");
                Resource resource = new FixtureResource("/content/fixture/jcr:content/root/hero",
                        "fixture/components/hero", props);
                HeroModel model = new HeroModel(resource);
                return "<h1>" + model.getTitle() + "</h1>";
            }
            case "/content/fixture/error.html":
                return ErrorModel.render(path);
            case "/content/fixture/loop.html":
                return "sum=" + LoopModel.sum(50);
            case "/content/fixture/lazy.html": {
                Object lazy = Class.forName("dev.aemdebug.fixture.models.LazyModel").getDeclaredConstructor()
                        .newInstance();
                return String.valueOf(lazy.getClass().getMethod("value").invoke(lazy));
            }
            default:
                throw new IllegalArgumentException("not found: " + path);
        }
    }
}
