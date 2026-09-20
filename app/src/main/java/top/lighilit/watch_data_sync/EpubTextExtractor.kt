package top.lighilit.watch_data_sync

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object EpubTextExtractor {

    fun isEpub(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() == "epub"

    fun readText(input: InputStream, name: String): String =
        if (isEpub(name)) extract(input) else input.bufferedReader().readText()

    fun extract(input: InputStream): String {
        val bytes = input.readBytes()
        val container = readEntry(bytes, "META-INF/container.xml")
            ?: error("Not a valid EPUB (missing META-INF/container.xml)")
        val opfPath = parseContainerRootfile(container)
        val opf = readEntry(bytes, opfPath) ?: error("Missing package document: $opfPath")
        val manifest = parseManifest(opf)
        val spine = parseSpine(opf)
        val base = opfPath.substringBeforeLast('/', "")
        val chapters = spine
            .mapNotNull { id -> manifest[id]?.let { href -> resolve(base, href) } }
            .mapNotNull { path -> readEntry(bytes, path) }
        return chapters.joinToString("\n\n") { htmlToText(it) }
    }

    private fun readEntry(bytes: ByteArray, path: String): String? {
        val target = normalize(path)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (normalize(entry.name) == target) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun parseContainerRootfile(xml: String): String {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("rootfile")
        val fullPath = (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull()
            ?.getAttribute("full-path")
        return decodePath(fullPath ?: error("Missing rootfile full-path"))
    }

    private fun parseManifest(xml: String): Map<String, String> {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("item")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val id = element.getAttribute("id")
                val href = element.getAttribute("href")
                if (id.isNotEmpty() && href.isNotEmpty()) put(id, href)
            }
        }
    }

    private fun parseSpine(xml: String): List<String> {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("itemref")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val idref = element.getAttribute("idref")
                if (idref.isNotEmpty()) add(idref)
            }
        }
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        return document.documentElement
    }

    private fun resolve(base: String, href: String): String {
        val decoded = decodePath(href)
        val joined = when {
            decoded.startsWith("/") -> decoded.removePrefix("/")
            base.isEmpty() -> decoded
            else -> "$base/$decoded"
        }
        val parts = ArrayDeque<String>()
        joined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun normalize(path: String): String {
        var result = path.replace('\\', '/')
        while (result.startsWith("./")) result = result.substring(2)
        while (result.startsWith("/")) result = result.substring(1)
        return result
    }

    private fun decodePath(path: String): String {
        return Regex("%([0-9a-fA-F]{2})").replace(path) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun htmlToText(html: String): String {
        var text = html
        text = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        text = Regex("(?is)<head\\b[^>]*>.*?</head\\s*>").replace(text, "")
        text = Regex("(?is)<script\\b[^>]*>.*?</script\\s*>").replace(text, "")
        text = Regex("(?is)<style\\b[^>]*>.*?</style\\s*>").replace(text, "")
        text = Regex("(?i)<br\\s*/?>").replace(text, "\n")
        text = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>").replace(text, "\n")
        text = Regex("<[^>]*>").replace(text, "")
        text = decodeEntities(text)
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private fun decodeEntities(text: String): String {
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: match.value
                else -> NAMED_ENTITIES[body] ?: match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int): String =
        String(Character.toChars(codePoint))

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "hellip" to "\u2026",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122"
    )
}
