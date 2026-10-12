package net.dankito.readability4j.processor

import net.dankito.readability4j.util.RegExUtil
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory

/**
 * Performs basic sanitization before starting the extraction process.
 */
open class Preprocessor(protected val regEx: RegExUtil = RegExUtil()) : ProcessorBase() {

    companion object {
        private val log = LoggerFactory.getLogger(Preprocessor::class.java)
    }


    /**
     * Prepare the HTML document for readability to scrape it.
     * This includes things like stripping CSS, replacing BR chains and handling font tags.
     */
    open fun prepareDocument(document: Document) {
        log.debug("Starting to prepare document")

        removeStyles(document)

        replaceBrs(document, regEx)

        replaceNodes(document, "font", "span")
    }

    open fun unwrapNoscriptImages(document: Document) {
        removePlaceholderImagesWithoutSource(document)

        document.getElementsByTag("noscript").forEach { noscript ->
            if(isSingleImage(noscript) == false) {
                return@forEach
            }

            val tmp = document.createElement("div")
            tmp.html(noscript.html())
            val newImage = tmp.selectFirst("img") ?: return@forEach

            val previousElement = noscript.previousElementSibling() ?: return@forEach
            if(isSingleImage(previousElement) == false) {
                return@forEach
            }

            val previousImage = if(previousElement.tagName() == "img") previousElement else previousElement.selectFirst("img")
                    ?: return@forEach

            previousImage.attributes().forEach { attribute ->
                val attributeValue = attribute.value.trim()
                if(attributeValue.isBlank()) {
                    return@forEach
                }

                if(attribute.key == "src" || attribute.key == "srcset" || attributeValue.contains("\\.(jpg|jpeg|png|webp)".toRegex(RegexOption.IGNORE_CASE))) {
                    if(newImage.attr(attribute.key) == attributeValue) {
                        return@forEach
                    }

                    val attributeName = if(newImage.hasAttr(attribute.key)) "data-old-${attribute.key}" else attribute.key
                    newImage.attr(attributeName, attributeValue)
                }
            }

            previousElement.replaceWith(newImage)
        }
    }

    open fun removeScriptsAndNoscripts(document: Document) {
        removeNodes(document, "script")
        removeNodes(document, "noscript")
    }


    protected open fun removePlaceholderImagesWithoutSource(document: Document) {
        document.getElementsByTag("img").forEach { image ->
            var shouldKeep = false

            image.attributes().forEach { attribute ->
                when(attribute.key) {
                    "src", "srcset", "data-src", "data-srcset" -> shouldKeep = true
                    else -> {
                        if(attribute.value.contains("\\.(jpg|jpeg|png|webp)".toRegex(RegexOption.IGNORE_CASE))) {
                            shouldKeep = true
                        }
                    }
                }
            }

            if(shouldKeep == false) {
                image.remove()
            }
        }
    }

    protected open fun isSingleImage(node: Element?): Boolean {
        var current = node

        while(current != null) {
            if(current.tagName() == "img") {
                return true
            }

            if(current.children().size != 1 || current.text().trim().isNotEmpty()) {
                return false
            }

            current = current.child(0)
        }

        return false
    }


    protected open fun removeStyles(document: Document) {
        removeNodes(document, "style")
    }

    protected open fun removeComments(node: Node) {
        var i = 0
        while (i < node.childNodeSize()) {
            val child = node.childNode(i)
            if(child.nodeName() == "#comment") {
                printAndRemove(child, "removeComments")
            }
            else {
                removeComments(child)
                i++
            }
        }
    }


    /**
     * Replaces 2 or more successive <br> elements with a single <p>.
     * Whitespace between <br> elements are ignored. For example:
     *   <div>foo<br>bar<br> <br><br>abc</div>
     * will become:
     *   <div>foo<br>bar<p>abc</p></div>
     */
    protected open fun replaceBrs(document: Document, regEx: RegExUtil) {
        document.body().select("br").forEach { br ->
            var next: Node? = br.nextSibling()

            // Whether 2 or more <br> elements have been found and replaced with a
            // <p> block.
            var replaced = false

            // If we find a <br> chain, remove the <br>s until we hit another element
            // or non-whitespace. This leaves behind the first <br> in the chain
            // (which will be replaced with a <p> later).
            next = nextElement(next, regEx)
            while(next != null && next.nodeName() == "br") {
                replaced = true
                val brSibling = (next as? Element)?.nextSibling()
                printAndRemove(next, "replaceBrs")
                next = nextElement(brSibling, regEx)
            }

            // If we removed a <br> chain, replace the remaining <br> with a <p>. Add
            // all sibling nodes as children of the <p> until we hit another <br>
            // chain.
            if(replaced) {
                val p = br.ownerDocument().createElement("p")
                br.replaceWith(p)

                next = p.nextSibling()
                while(next != null) {
                    // If we've hit another <br><br>, we're done adding children to this <p>.
                    if(next.nodeName() == "br") {
                        val nextElem = this.nextElement(next, regEx)
                        if(nextElem != null && nextElem.tagName() == "br")
                            break
                    }

                    if(isPhrasingContent(next) == false) {
                        break
                    }

                    // Otherwise, make this node a child of the new <p>.
                    val sibling = next.nextSibling()
                    p.appendChild(next)
                    next = sibling
                }

                while(p.childNodeSize() > 0 && isWhitespace(p.childNode(p.childNodeSize() - 1))) {
                    p.childNode(p.childNodeSize() - 1).remove()
                }

                if(p.parent()?.tagName() == "p") {
                    p.parent()?.tagName("div")
                }
            }
        }
    }

    protected open fun isPhrasingContent(node: Node): Boolean {
        if(node is TextNode) {
            return true
        }

        if(node is Element == false) {
            return false
        }

        val phrasingElements = setOf(
                "abbr", "audio", "b", "bdo", "br", "button", "cite", "code", "data", "datalist", "dfn",
                "em", "embed", "i", "img", "input", "kbd", "label", "mark", "math", "meter", "noscript",
                "object", "output", "progress", "q", "ruby", "samp", "script", "select", "small", "span",
                "strong", "sub", "sup", "textarea", "time", "var", "wbr"
        )

        return if(phrasingElements.contains(node.tagName())) {
            true
        }
        else if(node.tagName() == "a" || node.tagName() == "del" || node.tagName() == "ins") {
            node.childNodes().all { isPhrasingContent(it) }
        }
        else {
            false
        }
    }

    protected open fun isWhitespace(node: Node): Boolean {
        return (node is TextNode && node.text().trim().isEmpty()) || (node is Element && node.tagName() == "br")
    }

}
