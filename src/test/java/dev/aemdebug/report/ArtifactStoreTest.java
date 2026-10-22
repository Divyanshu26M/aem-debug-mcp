package dev.aemdebug.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStoreTest {

    @TempDir
    Path temp;

    @Test
    void writesAndReadsArtifactsUnderSequentialReportIds() {
        ArtifactStore store = new ArtifactStore(temp);
        String first = store.newReport();
        String second = store.newReport();
        assertThat(first).isEqualTo("rpt-0001");
        assertThat(second).isEqualTo("rpt-0002");
        store.write(first, "page.html", "<html/>");
        assertThat(new String(store.read(first, "page.html"), StandardCharsets.UTF_8)).isEqualTo("<html/>");
        assertThat(store.uri(first, "page.html")).isEqualTo("aemdbg://reports/rpt-0001/page.html");
        assertThat(store.list(first)).containsExactly("page.html");
        assertThat(new ArtifactStore(temp).newReport()).isEqualTo("rpt-0003");
    }

    @Test
    void parsesUrisAndRejectsTraversal() {
        String[] parts = ArtifactStore.parseUri("aemdbg://reports/rpt-0007/error-log.txt");
        assertThat(parts).containsExactly("rpt-0007", "error-log.txt");
        ArtifactStore store = new ArtifactStore(temp);
        assertThatThrownBy(() -> store.resolve("rpt-0001", "../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.resolve("..", "x.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactStore.parseUri("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mimeTypes() {
        assertThat(ArtifactStore.mimeType("a.png")).isEqualTo("image/png");
        assertThat(ArtifactStore.mimeType("a.json")).isEqualTo("application/json");
        assertThat(ArtifactStore.isText("rpt.txt")).isTrue();
        assertThat(ArtifactStore.isText("shot.jpg")).isFalse();
    }
}
