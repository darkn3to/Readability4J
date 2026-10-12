package net.dankito.readability4j.processor

import net.dankito.readability4j.model.ArticleMetadata
import net.dankito.readability4j.util.RegExUtil
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.regex.Pattern


open class MetadataParser(protected val regEx: RegExUtil = RegExUtil()): ProcessorBase() {


    open fun getJsonLdArticleMetadata(document: Document): ArticleMetadata {
        document.select("script[type=application/ld+json]").forEach { script ->
            val content = script.data().ifBlank { script.html() }
            val metadata = parseJsonLdMetadata(content)
            if(metadata.title.isNullOrBlank() == false || metadata.byline.isNullOrBlank() == false ||
                    metadata.excerpt.isNullOrBlank() == false || metadata.siteName.isNullOrBlank() == false ||
                    metadata.publishedTime.isNullOrBlank() == false) {
                return metadata
            }
        }

        return ArticleMetadata()
    }

    open fun getArticleMetadata(document: Document, jsonLdMetadata: ArticleMetadata = ArticleMetadata()): ArticleMetadata {
        val metadata = ArticleMetadata()
        val values = HashMap<String, String>()

        val propertyPattern = Pattern.compile("\\s*(article|dc|dcterm|og|twitter)\\s*:\\s*(author|creator|description|published_time|title|site_name)\\s*", Pattern.CASE_INSENSITIVE)
        val namePattern = Pattern.compile("^\\s*(?:(dc|dcterm|og|twitter|parsely|weibo:(article|webpage))\\s*[-\\.:]\\s*)?(author|creator|pub-date|description|title|site_name)\\s*$", Pattern.CASE_INSENSITIVE)

        document.select("meta").forEach { element ->
            val elementName = element.attr("name")
            val elementProperty = element.attr("property")
            val content = element.attr("content")

            if(content.isBlank()) {
                return@forEach
            }

            var normalizedName: String? = null

            if(elementProperty.isNotBlank()) {
                val matches = propertyPattern.matcher(elementProperty)
                if(matches.find()) {
                    normalizedName = matches.group().toLowerCase().replace("\\s".toRegex(), "")
                }
            }

            if(normalizedName == null && elementName.isNotBlank() && namePattern.matcher(elementName).find()) {
                normalizedName = elementName.toLowerCase().replace("\\s".toRegex(), "").replace(".", ":")
            }

            normalizedName?.let {
                values[it] = content.trim()
            }
        }

        metadata.title = firstNonBlank(
                jsonLdMetadata.title,
                values["dc:title"],
                values["dcterm:title"],
                values["og:title"],
                values["weibo:article:title"],
                values["weibo:webpage:title"],
                values["title"],
                values["twitter:title"],
                values["parsely-title"],
                getArticleTitle(document)
        )

        val articleAuthor = values["article:author"]?.let { if(isUrl(it)) null else it }

        metadata.byline = firstNonBlank(
                jsonLdMetadata.byline,
                values["dc:creator"],
                values["dcterm:creator"],
                values["author"],
                values["parsely-author"],
                articleAuthor
        )

        metadata.excerpt = firstNonBlank(
                jsonLdMetadata.excerpt,
                values["dc:description"],
                values["dcterm:description"],
                values["og:description"],
                values["weibo:article:description"],
                values["weibo:webpage:description"],
                values["description"],
                values["twitter:description"]
        )

        metadata.siteName = firstNonBlank(jsonLdMetadata.siteName, values["og:site_name"])
        metadata.publishedTime = firstNonBlank(jsonLdMetadata.publishedTime, values["article:published_time"], values["parsely-pub-date"])

        metadata.title = unescapeHtmlEntities(metadata.title)
        metadata.byline = unescapeHtmlEntities(metadata.byline)
        metadata.excerpt = unescapeHtmlEntities(metadata.excerpt)
        metadata.siteName = unescapeHtmlEntities(metadata.siteName)
        metadata.publishedTime = unescapeHtmlEntities(metadata.publishedTime)

        metadata.charset = document.charset()?.name()

        return metadata
    }

    protected open fun parseJsonLdMetadata(content: String): ArticleMetadata {
        val metadata = ArticleMetadata()
        val cleanedContent = content.replace("^\\s*<!\\[CDATA\\[|\\]\\]>\\s*$".toRegex(), "")

        val typeValue = extractJsonStringValue(cleanedContent, "@type")
        if(typeValue.isNullOrBlank() || isJsonLdArticleType(typeValue) == false) {
            return metadata
        }

        val name = extractJsonStringValue(cleanedContent, "name")
        val headline = extractJsonStringValue(cleanedContent, "headline")

        metadata.title = when {
            name.isNullOrBlank() == false && headline.isNullOrBlank() == false -> name
            name.isNullOrBlank() == false -> name
            else -> headline
        }

        metadata.byline = extractAuthor(cleanedContent)
        metadata.excerpt = extractJsonStringValue(cleanedContent, "description")
        metadata.siteName = extractNestedJsonStringValue(cleanedContent, "publisher", "name")
        metadata.publishedTime = extractJsonStringValue(cleanedContent, "datePublished")

        return metadata
    }

    protected open fun extractAuthor(content: String): String? {
        extractNestedJsonStringValue(content, "author", "name")?.let { return it }

        val authorArrayMatch = "\"author\"\\s*:\\s*\\[(.*?)]".toRegex(RegexOption.DOT_MATCHES_ALL).find(content)
        authorArrayMatch?.groupValues?.get(1)?.let { arrayContent ->
            val names = "\"name\"\\s*:\\s*\"(.*?)\"".toRegex(RegexOption.DOT_MATCHES_ALL)
                    .findAll(arrayContent)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotBlank() }
                    .toList()

            if(names.isNotEmpty()) {
                return names.joinToString(", ")
            }
        }

        return null
    }

    protected open fun extractNestedJsonStringValue(content: String, objectKey: String, valueKey: String): String? {
        val objectMatch = "\"$objectKey\"\\s*:\\s*\\{(.*?)\\}".toRegex(RegexOption.DOT_MATCHES_ALL).find(content)
        return objectMatch?.groupValues?.get(1)?.let { extractJsonStringValue(it, valueKey) }
    }

    protected open fun extractJsonStringValue(content: String, key: String): String? {
        val pattern = Pattern.compile("\\\"$key\\\"\\s*:\\s*\\\"(.*?)\\\"", Pattern.DOTALL)
        val matcher = pattern.matcher(content)

        if(matcher.find()) {
            return matcher.group(1).trim()
        }

        return null
    }

    protected open fun isJsonLdArticleType(type: String): Boolean {
        return type.contains("Article") || type.contains("Posting") || type.contains("Report")
    }

    protected open fun isUrl(value: String): Boolean {
        return value.startsWith("http://") || value.startsWith("https://")
    }

    protected open fun unescapeHtmlEntities(value: String?): String? {
        return value?.let { Parser.unescapeEntities(it, false) }
    }

    protected open fun firstNonBlank(vararg values: String?): String? {
        values.forEach { value ->
            if(value.isNullOrBlank() == false) {
                return value
            }
        }

        return null
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
            val match = doc.select("h1, h2").any { it.wholeText().trim() == curTitle.trim() }

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

        curTitle = regEx.normalize(curTitle.trim())
        // If we now have 4 words or fewer as our title, and either no
        // 'hierarchical' separators (\, /, > or ») were found in the original
        // title or we decreased the number of words by more than 1 word, use
        // the original title.
        val curTitleWordCount = wordCount(curTitle)
        if(curTitleWordCount <= 4 &&
            (!titleHadHierarchicalSeparators ||
            curTitleWordCount != wordCount(origTitle.replace(" [\\|\\-\\/>»] ".toRegex(), "")) - 1)) {
            curTitle = origTitle
        }

        return curTitle
    }

    protected open fun wordCount(str: String): Int {
        return str.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
    }

}
