package dev.aemdebug.jdi;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionTest {

    private static Map<String, Object> vars(Object... keyValues) {
        Map<String, Object> vars = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            vars.put((String) keyValues[i], keyValues[i + 1]);
        }
        return vars;
    }

    @Test
    void comparesNumbersAndNull() {
        assertThat(Condition.parse("i == 7").test(vars("i", 7))).isTrue();
        assertThat(Condition.parse("i == 7").test(vars("i", 8))).isFalse();
        assertThat(Condition.parse("i != 7").test(vars("i", 8))).isTrue();
        assertThat(Condition.parse("count > 2").test(vars("count", 3))).isTrue();
        assertThat(Condition.parse("count < 2").test(vars("count", 3))).isFalse();
        assertThat(Condition.parse("title == null").test(vars("title", null))).isTrue();
        assertThat(Condition.parse("title == null").test(vars("title", "Welcome"))).isFalse();
    }

    @Test
    void combinesStringOperatorsWithBooleanLogic() {
        Map<String, Object> hero = vars("count", 3, "name", "hero", "enabled", true);
        assertThat(Condition.parse("count > 2 && name startsWith 'he'").test(hero)).isTrue();
        assertThat(Condition.parse("count > 5 && name startsWith 'he'").test(hero)).isFalse();
        assertThat(Condition.parse("!enabled || name contains \"x\"").test(hero)).isFalse();
        assertThat(Condition.parse("!enabled || name endsWith \"ro\"").test(hero)).isTrue();
        assertThat(Condition.parse("name matches '^h.*o$'").test(hero)).isTrue();
        assertThat(Condition.parse("title == \"Welcome\"").test(vars("title", "Welcome"))).isTrue();
    }

    @Test
    void resolvesDottedNamesInMaps() {
        Map<String, Object> resource = new HashMap<>();
        resource.put("path", "/content/site/hero");
        assertThat(Condition.parse("resource.path contains 'hero'").test(vars("resource", resource))).isTrue();
    }

    @Test
    void formatsLogpointMessages() {
        assertThat(Condition.format("i={i} total={total}", vars("i", 7, "total", 21))).isEqualTo("i=7 total=21");
        assertThat(Condition.format("no placeholders", vars())).isEqualTo("no placeholders");
        assertThat(Condition.format("open {brace", vars())).isEqualTo("open {brace");
    }

    @Test
    void rejectsTrailingTokens() {
        assertThatThrownBy(() -> Condition.parse("i == 7 8")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truthiness() {
        assertThat(Condition.truthy(null)).isFalse();
        assertThat(Condition.truthy("")).isFalse();
        assertThat(Condition.truthy(0)).isFalse();
        assertThat(Condition.truthy(Boolean.FALSE)).isFalse();
        assertThat(Condition.truthy("x")).isTrue();
        assertThat(Condition.truthy(2)).isTrue();
        assertThat(Condition.truthy(new Object())).isTrue();
    }
}
