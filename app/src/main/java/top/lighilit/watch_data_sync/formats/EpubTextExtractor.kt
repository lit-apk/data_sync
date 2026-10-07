package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@DocumentBackend
internal object EpubTextExtractor : DocumentFormat {
    override val mimeTypes = listOf("application/epub+zip")
    override val richText = true
    override val chaptered = true
    override fun matches(name: String) = name.substringAfterLast('.', "").equals("epub", true)
    override fun source(context: Context, uri: Uri, name: String) = source {
        context.contentResolver.openInputStream(uri) ?: error("Unable to open $name")
    }
    override fun source(file: File, name: String) = source { file.inputStream() }
    override fun readText(input: InputStream, name: String) = extract(input)
    override fun preserveOriginalOnBackup() = true

    fun source(openStream: () -> InputStream): TextSource = EpubTextSource(openStream)

    internal fun parseContainerRootfileForSource(xml: String) = parseContainerRootfile(xml)
    internal fun parseManifestForSource(xml: String) = parseManifest(xml)
    internal fun parseSpineForSource(xml: String) = parseSpine(xml)
    internal fun normalizeForSource(path: String) = normalize(path)
    internal fun resolveForSource(base: String, href: String) = resolve(base, href)
    internal fun parseXmlForSource(xml: String) = parseXml(xml)
    internal fun parseNavigationIdForSource(xml: String) = parseNavigationId(xml)
    internal fun parseSpineTocForSource(xml: String) = parseSpineToc(xml)
    internal fun parseNcxIdForSource(xml: String) = parseNcxId(xml)

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

    private fun parseSpineToc(xml: String): String? {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("spine")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .firstOrNull()?.getAttribute("toc")?.ifBlank { null }
    }

    private fun parseNavigationId(xml: String): String? {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .firstOrNull { element ->
                element.getAttribute("properties").split(Regex("\\s+")).contains("nav")
            }?.getAttribute("id")?.ifBlank { null }
    }

    private fun parseNcxId(xml: String): String? {
        val root = parseXml(xml)
        val nodes = root.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
            .firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" }
            ?.getAttribute("id")?.ifBlank { null }
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

    internal fun decodeEntitiesForSource(text: String): String {
        return decodeEntities(text)
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

private class EpubTextSource(
    private val openStream: () -> InputStream,
    private val chapterPath: String? = null
) : TextSource {
    override val chaptered = true
    private val metadata by lazy { readMetadata() }

    override fun chapters(): List<TextChapter> {
        val titles = metadata.navigationTitles(openStream)
        return metadata.paths.mapIndexed { index, path ->
            TextChapter(
                index = index,
                title = titles[path] ?: filenameTitle(path, index),
                startOffset = 0
            )
        }
    }

    private fun filenameTitle(path: String, index: Int): String =
        path.substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { "Chapter ${index + 1}" }

    override fun chapterSource(index: Int): TextSource =
        EpubTextSource(openStream, metadata.paths.getOrNull(index))


    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? {
        require(startOffset >= 0) { "startOffset must not be negative" }
        require(maxChars > 0) { "maxChars must be positive" }

        val epub = metadata
        val paths = chapterPath?.let { listOf(it) } ?: epub.paths
        EpubTextReader(openStream, paths).use { reader ->
                skipChars(reader, startOffset)
                val buffer = CharArray(maxChars + 1)
                val count = readUpTo(reader, buffer)
                if (count <= 0) return null

                val returnedCount = if (
                    count > maxChars && Character.isHighSurrogate(buffer[maxChars - 1])
                ) maxChars - 1 else minOf(count, maxChars)
                return SourcePart(
                    text = String(buffer, 0, returnedCount),
                    endOffset = startOffset + returnedCount,
                    endOfSource = count <= maxChars
                )
        }
    }

    private fun readMetadata(): EpubMetadata {
        val container = readEntry("META-INF/container.xml")
            ?: error("Not a valid EPUB (missing META-INF/container.xml)")
        val opfPath = EpubTextExtractor.parseContainerRootfileForSource(container)
        val opf = readEntry(opfPath) ?: error("Missing package document: $opfPath")
        return EpubMetadata(
            opfPath.substringBeforeLast('/', ""),
            EpubTextExtractor.parseManifestForSource(opf),
            EpubTextExtractor.parseSpineForSource(opf),
            EpubTextExtractor.parseManifestForSource(opf).let { manifest ->
                val id = EpubTextExtractor.parseNavigationIdForSource(opf)
                id?.let { manifest[it] }
                    ?: manifest[EpubTextExtractor.parseSpineTocForSource(opf)]
            }?.let { EpubTextExtractor.resolveForSource(opfPath.substringBeforeLast('/', ""), it) },
            EpubTextExtractor.parseManifestForSource(opf).let { manifest ->
                val id = EpubTextExtractor.parseSpineTocForSource(opf)
                    ?: EpubTextExtractor.parseNcxIdForSource(opf)
                id?.let { manifest[it] }
            }?.let { EpubTextExtractor.resolveForSource(opfPath.substringBeforeLast('/', ""), it) }
        )
    }

    private fun readEntry(path: String): String? {
        val target = EpubTextExtractor.normalizeForSource(path)
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (EpubTextExtractor.normalizeForSource(entry.name) == target) {
                        return zip.bufferedReader().readText()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun readEntryForMetadata(path: String): String? = readEntry(path)

    private fun skipChars(reader: Reader, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (reader.read() < 0) return
            else remaining--
        }
    }

    private fun readUpTo(reader: Reader, buffer: CharArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = reader.read(buffer, total, buffer.size - total)
            if (count < 0) break
            if (count > 0) total += count
            else {
                val character = reader.read()
                if (character < 0) break
                buffer[total++] = character.toChar()
            }
        }
        return total
    }

    private data class EpubMetadata(
        val base: String,
        val manifest: Map<String, String>,
        val spine: List<String>,
        val navigationPath: String?,
        val ncxPath: String?
    ) {
        val paths: List<String>
            get() = spine.mapNotNull { manifest[it] }
                .map { EpubTextExtractor.resolveForSource(base, it) }
                .filterNot { path ->
                    path.substringAfterLast('/').substringBeforeLast('.')
                        .lowercase() in setOf("cover", "info", "info2", "content", "img")
                }

        fun openTextReader(openStream: () -> InputStream): Reader {
            return EpubTextReader(openStream, paths)
        }

        fun navigationTitles(openStream: () -> InputStream): Map<String, String> {
            val path = navigationPath ?: return emptyMap()
            val html = EpubTextSource(openStream).readEntryForMetadata(path) ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            val anchorPattern = Regex(
                "(?is)<a\\b[^>]*href=[\\\"']([^\\\"'#]+)(?:#[^\\\"']*)?[\\\"'][^>]*>(.*?)</a>"
            )
            anchorPattern.findAll(html).forEach { match ->
                val href = EpubTextExtractor.resolveForSource(base, match.groupValues[1])
                val title = EpubTextExtractor.decodeEntitiesForSource(
                    Regex("<[^>]*>").replace(match.groupValues[2], "")
                ).replace(Regex("\\s+"), " ").trim().take(80)
                if (title.isNotEmpty()) result[href] = title
            }
            if (result.isNotEmpty()) return result
            val ncx = ncxPath?.let { EpubTextSource(openStream).readEntryForMetadata(it) } ?: return result
            val root = EpubTextExtractor.parseXmlForSource(ncx)
            val points = root.getElementsByTagName("navPoint")
            for (index in 0 until points.length) {
                val point = points.item(index) as? Element ?: continue
                val label = point.getElementsByTagName("text").item(0)?.textContent?.trim().orEmpty()
                val src = point.getElementsByTagName("content").item(0)
                    ?.let { (it as? Element)?.getAttribute("src") }.orEmpty()
                if (label.isNotEmpty() && src.isNotEmpty()) {
                    result[EpubTextExtractor.resolveForSource(base, src.substringBefore('#'))] = label.take(80)
                }
            }
            return result
        }
    }
}

private class EpubTextReader(
    private val openStream: () -> InputStream,
    private val paths: List<String>
) : Reader() {
    private var chapterIndex = 0
    private var chapterReader: Reader? = null
    private var ignoredTag: String? = null
    private val pending = ArrayDeque<Char>()
    private var lookahead = -2
    private var hasOutput = false
    private var afterBlockBreak = false

    override fun read(charBuffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            while (pending.isEmpty()) {
                if (!fillPending()) {
                    val reader = nextChapter() ?: return if (count == 0) -1 else count
                    chapterReader = reader
                    if (chapterIndex > 1) {
                        pending.addLast('\n')
                        pending.addLast('\n')
                    }
                }
            }
            charBuffer[offset + count] = pending.removeFirst()
            count++
        }
        return if (count == 0) -1 else count
    }

    private fun fillPending(): Boolean {
        while (pending.isEmpty()) {
            val first = readInput()
            if (first < 0) return false
            if (first != '<'.code) {
                if (ignoredTag == null) {
                    if (first == '&'.code) {
                        val entity = StringBuilder("&")
                        var character = readInput()
                        while (character >= 0 && entity.length <= 32) {
                            entity.append(character.toChar())
                            if (character == ';'.code) break
                            character = readInput()
                        }
                        EpubTextExtractor.decodeEntitiesForSource(entity.toString())
                            .forEach { pending.addLast(it) }
                    } else if (Character.isWhitespace(first)) {
                        var character = readInput()
                        while (character >= 0 && Character.isWhitespace(character)) {
                            character = readInput()
                        }
                        if (character >= 0) lookahead = character
                        if (hasOutput && !afterBlockBreak && character >= 0 && character != '<'.code) {
                            pending.addLast(' ')
                        }
                    } else {
                        pending.addLast(first.toChar())
                    }
                    if (pending.isNotEmpty()) {
                        hasOutput = true
                        afterBlockBreak = false
                    }
                }
                continue
            }

            val tag = StringBuilder("<")
            var character = readInput()
            while (character >= 0) {
                tag.append(character.toChar())
                if (character == '>'.code) break
                character = readInput()
            }
            val tagText = tag.toString()
            val closing = Regex("^</\\s*([A-Za-z0-9]+)").find(tagText)?.groupValues?.get(1)?.lowercase()
            val opening = Regex("^<\\s*([A-Za-z0-9]+)").find(tagText)?.groupValues?.get(1)?.lowercase()
            if (ignoredTag != null) {
                if (closing == ignoredTag) ignoredTag = null
            } else if (opening in setOf("script", "style", "head")) {
                ignoredTag = opening
            } else if (opening == "br") {
                pending.addLast('\n')
                afterBlockBreak = true
            } else if (closing in BLOCK_TAGS) {
                pending.addLast('\n')
                pending.addLast('\n')
                afterBlockBreak = true
            }
        }
        return pending.isNotEmpty()
    }

    private fun readInput(): Int {
        if (lookahead != -2) {
            val value = lookahead
            lookahead = -2
            return value
        }
        return chapterReader?.read() ?: -1
    }

    private fun nextChapter(): Reader? {
        chapterReader?.close()
        chapterReader = null
        while (chapterIndex < paths.size) {
            val target = paths[chapterIndex++]
            val zip = ZipInputStream(openStream())
            var entry = zip.nextEntry
            while (entry != null) {
                if (EpubTextExtractor.normalizeForSource(entry.name) == target) {
                    return InputStreamReader(zip, Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
            zip.close()
        }
        return null
    }

    override fun close() {
        chapterReader?.close()
        chapterReader = null
    }

    companion object {
        private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "article")
    }
}
