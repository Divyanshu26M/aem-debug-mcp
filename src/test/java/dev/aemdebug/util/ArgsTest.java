package dev.aemdebug.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgsTest {

    @Test
    void convertsLenientTypes() {
        Args args = Args.of("depth", "3", "flag", "yes", "list", "a, b,,c", "num", 7.9, "blank", "  ");
        assertThat(args.integer("depth", 0)).isEqualTo(3);
        assertThat(args.integer("num", 0)).isEqualTo(7);
        assertThat(args.bool("flag", false)).isTrue();
        assertThat(args.strList("list")).containsExactly("a", "b", "c");
        assertThat(args.str("blank")).isNull();
        assertThat(args.has("blank")).isFalse();
        assertThat(args.integer("missing", 5, 0, 3)).isEqualTo(3);
    }

    @Test
    void choiceValidatesAllowedValues() {
        Args args = Args.of("mode", "EDIT");
        assertThat(args.choice("mode", "disabled", "disabled", "edit")).isEqualTo("edit");
        assertThat(args.choice("other", "disabled", "disabled", "edit")).isEqualTo("disabled");
        assertThatThrownBy(() -> Args.of("mode", "x").choice("mode", null, "a", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be one of a, b");
    }

    @Test
    void parsesJsonEncodedListsAndMaps() {
        Args args = Args.of("bps", "[{\"location\":\"A:1\"},{\"location\":\"B:2\"}]", "params", "{\"type\":\"cq:Page\"}",
                "list", List.of("x", "y"));
        assertThat(args.mapList("bps")).hasSize(2);
        assertThat(args.mapList("bps").get(1)).containsEntry("location", "B:2");
        assertThat(args.map("params")).containsEntry("type", "cq:Page");
        assertThat(args.strList("list")).containsExactly("x", "y");
    }

    @Test
    void requireStrFailsClearly() {
        assertThatThrownBy(() -> new Args(Map.of()).requireStr("path"))
                .hasMessageContaining("Missing required argument 'path'");
    }

    @Test
    void invalidNumberAndBooleanAreRejected() {
        assertThatThrownBy(() -> Args.of("n", "abc").integer("n", 1)).hasMessageContaining("must be a number");
        assertThatThrownBy(() -> Args.of("b", "maybe").bool("b", false)).hasMessageContaining("true or false");
    }
}
