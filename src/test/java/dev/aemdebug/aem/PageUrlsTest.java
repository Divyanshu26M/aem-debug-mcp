package dev.aemdebug.aem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageUrlsTest {

    @Test
    void normalizesPagePaths() {
        assertThat(PageUrls.toRequestPath("/content/wknd/us/en")).isEqualTo("/content/wknd/us/en.html");
        assertThat(PageUrls.toRequestPath("content/wknd/us/en.html")).isEqualTo("/content/wknd/us/en.html");
        assertThat(PageUrls.toRequestPath("/content/wknd/us/en.model.json")).isEqualTo("/content/wknd/us/en.model.json");
        assertThat(PageUrls.toRequestPath("http://localhost:4502/content/a/b.html?x=1")).isEqualTo("/content/a/b.html?x=1");
        assertThat(PageUrls.toRequestPath("/content/a/b?x=1")).isEqualTo("/content/a/b.html?x=1");
        assertThat(PageUrls.toRequestPath("/libs/granite/core/content/login.html")).isEqualTo("/libs/granite/core/content/login.html");
        assertThatThrownBy(() -> PageUrls.toRequestPath("https://www.example.com/content/a.html"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void addsAndReplacesWcmMode() {
        assertThat(PageUrls.withWcmMode("/content/a.html", "disabled")).isEqualTo("/content/a.html?wcmmode=disabled");
        assertThat(PageUrls.withWcmMode("/content/a.html?x=1&wcmmode=edit", "disabled"))
                .isEqualTo("/content/a.html?x=1&wcmmode=disabled");
        assertThat(PageUrls.withWcmMode("/content/a.html", "none")).isEqualTo("/content/a.html");
    }

    @Test
    void extractsContentPath() {
        assertThat(PageUrls.contentPath("/content/site/en.html?wcmmode=disabled")).isEqualTo("/content/site/en");
        assertThat(PageUrls.contentPath("/content/site/en.print.html")).isEqualTo("/content/site/en");
        assertThat(PageUrls.contentPath("/content/site/en")).isEqualTo("/content/site/en");
    }
}
