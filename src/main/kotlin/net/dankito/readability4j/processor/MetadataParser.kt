package net.dankito.readability4j.processor

import net.dankito.readability4j.model.ArticleMetadata
import net.dankito.readability4j.util.RegExUtil
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.util.regex.Pattern


open class MetadataParser(protected val regEx: RegExUtil = RegExUtil()): ProcessorBase() {

    open fun getJsonLdArticleMetadata(document: Document): ArticleMetadata {
        val metadata = ArticleMetadata()

        document.select("script[type=application/ld+json]").forEach { script ->
            val content = script.data().ifBlank { script.html() }

            extractJsonStringValue(content, "headline")?.let { if(metadata.title == null) metadata.title = it }
            extractJsonStringValue(content, "description")?.let { if(metadata.excerpt == null) metadata.excerpt = it }
            extractJsonStringValue(content, "datePublished")?.let { metadata.publishedTime = it }
            extractNestedJsonStringValue(content, "publisher", "name")?.let { metadata.siteName = it }

            extractNestedJsonStringValue(content, "author", "name")?.let { if(metadata.byline == null) metadata.byline = it }
        }

        metadata.title = unescapeHtmlEntities(metadata.title)
        metadata.byline = unescapeHtmlEntities(metadata.byline)
        metadata.excerpt = unescapeHtmlEntities(metadata.excerpt)
        metadata.siteName = unescapeHtmlEntities(metadata.siteName)

        return metadata
    }


    open fun getArticleMetadata(document: Document, jsonLdMetadata: ArticleMetadata = ArticleMetadata()): ArticleMetadata {
        val metadata = ArticleMetadata()
        val values = HashMap<String, String>()

        // Match "description", or Twitter's "twitter:description" (Cards) in name attribute.
        val namePattern = Pattern.compile("^\\s*(?:(dc|dcterm|og|twitter|parsely|weibo:(article|webpage))\\s*[-\\.:]\\s*)?(author|creator|pub-date|description|title|site_name)\\s*$", Pattern.CASE_INSENSITIVE)

        // Match Open Graph / dc / article properties.
        val propertyPattern = Pattern.compile("\\s*(article|dc|dcterm|og|twitter)\\s*:\\s*(author|creator|description|published_time|title|site_name)\\s*", Pattern.CASE_INSENSITIVE)

        document.select("meta").forEach { element ->
            val elementName = element.attr("name")
            val elementProperty = element.attr("property")

            var name: String? = null
            if(elementProperty.isNotBlank() && propertyPattern.matcher(elementProperty).find()) {
                name = elementProperty.toLowerCase().replace("\\s".toRegex(), "")
            } else if(namePattern.matcher(elementName).find()) {
                name = elementName.toLowerCase().replace("\\s".toRegex(), "").replace(".", ":")
            }

            if(name != null) {
                val content = element.attr("content")
                if(content.isNullOrBlank() == false) {
                    values[name] = content.trim().replace("  ", " ")
                }
            }
        }

        metadata.excerpt = values["description"] ?:
                values["og:description"] ?:
                values["twitter:description"] ?:
                values["dc:description"] ?:
                values["dcterm:description"] ?:
                values["weibo:article:description"] ?:
                values["weibo:webpage:description"] ?:
                jsonLdMetadata.excerpt

        metadata.title = getArticleTitle(document)
        if(metadata.title.isNullOrBlank()) {
            metadata.title = values["og:title"] ?:
                    values["twitter:title"] ?:
                    values["dc:title"] ?:
                    values["dcterm:title"] ?:
                    values["weibo:article:title"] ?:
                    values["weibo:webpage:title"] ?:
                    values["title"] ?:
                    values["parsely-title"] ?:
                    jsonLdMetadata.title ?:
                    ""
        }

        if(metadata.byline.isNullOrBlank()) {
            val articleAuthor = values["article:author"]?.let { if(it.startsWith("http://") || it.startsWith("https://")) null else it }
            metadata.byline = values["author"] ?:
                    values["dc:creator"] ?:
                    values["dcterm:creator"] ?:
                    values["parsely-author"] ?:
                    articleAuthor ?:
                    jsonLdMetadata.byline
        }

        metadata.siteName = jsonLdMetadata.siteName ?: values["og:site_name"]
        metadata.publishedTime = jsonLdMetadata.publishedTime ?: values["article:published_time"] ?: values["parsely-pub-date"]

        metadata.title = unescapeHtmlEntities(metadata.title)
        metadata.byline = unescapeHtmlEntities(metadata.byline)
        metadata.excerpt = unescapeHtmlEntities(metadata.excerpt)
        metadata.siteName = unescapeHtmlEntities(metadata.siteName)
        metadata.publishedTime = unescapeHtmlEntities(metadata.publishedTime)

        metadata.charset = document.charset()?.name()

        return metadata
    }

    protected open fun getArticleTitle(doc: Document): String {
        var curTitle = ""
        var origTitle = ""

        try {
            origTitle = doc.title()
            curTitle = origTitle

            // If they had an element with id "title" in their HTML
            if(curTitle.isBlank()) {
                doc.select("#title").first()?.let { elementWithIdTitle ->
                    origTitle = getInnerText(elementWithIdTitle, regEx)
                    curTitle = origTitle
                }
            }
        } catch(e: Exception) {/* ignore exceptions setting the title. */}

        var titleHadHierarchicalSeparators = false

        // If there's a separator in the title, first remove the final part
        if(curTitle.contains(" [\\|\\-\\/>»] ".toRegex())) {
            titleHadHierarchicalSeparators = curTitle.contains(" [\\/>»] ".toRegex())
            curTitle = origTitle.replace("(.*)[\\|\\-\\/>»] .*".toRegex(RegexOption.IGNORE_CASE), "$1")

            // If the resulting title is too short (3 words or fewer), remove
            // the first part instead:
            if(wordCount(curTitle) < 3) {
                curTitle = origTitle.replace("[^\\|\\-\\/>»]*[\\|\\-\\/>»](.*)".toRegex(RegexOption.IGNORE_CASE), "$1")
            }
        }
        else if(curTitle.contains(": ")) {
            // Check if we have an heading containing this exact string, so we
            // could assume it's the full title.
            val match = doc.select("h1, h2").filter { it.wholeText() == curTitle }.size > 0

            // If we don't, let's extract the title out of the original title string.
            if(match == false) {
                curTitle = origTitle.substring(origTitle.lastIndexOf(':') + 1)

                // If the title is now too short, try the first colon instead:
                if(wordCount(curTitle) < 3) {
                    curTitle = origTitle.substring(origTitle.indexOf(':') + 1)
                }
                // But if we have too many words before the colon there's something weird
                // with the titles and the H tags so let's just use the original title instead
                else if(wordCount(origTitle.substring(0, origTitle.indexOf(':'))) > 5) {
                    curTitle = origTitle
                }
            }
        }
        else if(curTitle.length > 150 || curTitle.length < 15) {
            val hOnes = doc.getElementsByTag("h1")

            if(hOnes.size == 1) {
                curTitle = getInnerText(hOnes[0], regEx)
            }
        }

        curTitle = curTitle.trim()
        // If we now have 4 words or fewer as our title, and either no
        // 'hierarchical' separators (\, /, > or ») were found in the original
        // title or we decreased the number of words by more than 1 word, use
        // the original title.
        val curTitleWordCount = wordCount(curTitle)
        if(curTitleWordCount <= 4 &&
            (!titleHadHierarchicalSeparators ||
            curTitleWordCount != wordCount(origTitle.replace("[\\|\\-\\/>»]+".toRegex(), "")) - 1)) {
            curTitle = origTitle
        }

        return curTitle
    }

    protected open fun wordCount(str: String): Int {
        return str.split("\\s+".toRegex()).size
    }

    protected open fun extractJsonStringValue(json: String, key: String): String? {
        val matcher = Pattern.compile("\"$key\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL).matcher(json)
        return if(matcher.find()) matcher.group(1).trim() else null
    }

    protected open fun extractNestedJsonStringValue(json: String, objectKey: String, key: String): String? {
        val objectMatcher = Pattern.compile("\"$objectKey\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(json)
        if(objectMatcher.find() == false) {
            return null
        }

        return extractJsonStringValue(objectMatcher.group(1), key)
    }

    protected open fun unescapeHtmlEntities(value: String?): String? {
        return value?.let { Parser.unescapeEntities(it, false) }
    }

}