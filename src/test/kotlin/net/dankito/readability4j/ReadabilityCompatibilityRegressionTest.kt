package net.dankito.readability4j

import net.dankito.readability4j.model.ReadabilityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityCompatibilityRegressionTest {

    @Test
    fun fallbackAttemptsKeepLongestResult() {
        val html = """
            <html><head><title>Test</title></head><body>
              <div class='comment'>This is the real article content with enough meaningful words to exceed the threshold and must survive fallback extraction.</div>
              <div>short</div>
            </body></html>
        """.trimIndent()

        val readability = Readability4J("https://example.com", html, ReadabilityOptions(wordThreshold = 100))
        val article = readability.parse()

        assertTrue(article.textContent!!.contains("real article content"))
    }

    @Test
    fun jsonLdMetadataIsExtractedBeforeScriptRemoval() {
        val html = """
            <html><head>
              <title>Fallback title</title>
              <script type="application/ld+json">
                {
                  "@context":"https://schema.org",
                  "@type":"NewsArticle",
                  "headline":"JSON-LD title",
                  "datePublished":"2025-03-04T12:00:00Z",
                  "publisher":{"name":"Example Publisher"},
                  "author":{"name":"Author Name"},
                  "description":"JSON-LD excerpt"
                }
              </script>
            </head><body><article><p>Body text for parsing.</p></article></body></html>
        """.trimIndent()

        val article = Readability4J("https://example.com", html).parse()

        assertEquals("Fallback title", article.title)
        assertEquals("Author Name", article.byline)
        assertEquals("JSON-LD excerpt", article.excerpt)
        assertEquals("Example Publisher", article.siteName)
        assertEquals("2025-03-04T12:00:00Z", article.publishedTime)
    }

    @Test
    fun bylineCanBeDetectedFromItempropAuthor() {
        val html = """
            <html><head><title>Title</title></head><body>
              <div itemprop="author">Jane Doe</div>
              <article><p>This article contains enough text to be retained by the extractor with minimal cleanup.</p></article>
            </body></html>
        """.trimIndent()

        val article = Readability4J("https://example.com", html).parse()

        assertEquals("Jane Doe", article.byline)
    }

    @Test
    fun noscriptImageCanReplacePlaceholderImage() {
        val html = """
            <html><head><title>Title</title></head><body>
              <article>
                <img src="" />
                <noscript><img src="https://cdn.example.com/real.jpg" /></noscript>
                <p>Meaningful article text to keep this section.</p>
              </article>
            </body></html>
        """.trimIndent()

        val article = Readability4J("https://example.com", html).parse()
        val extracted = article.content

        assertNotNull(extracted)
        assertTrue(extracted!!.contains("https://cdn.example.com/real.jpg"))
    }
}
