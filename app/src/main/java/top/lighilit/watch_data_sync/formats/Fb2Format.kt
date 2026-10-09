package top.lighilit.watch_data_sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * FictionBook 2: one XML file with text sections and base64 `<binary>` images.
 * Each top-level `<section>` of the main `<body>` is a chapter, like EPUB; images
 * referenced by `<image l:href="#id"/>` are sent alongside the chapter text.
 */
@DocumentBackend(MimeType.FB2)
internal class Fb2Format : DocumentFormat {
    override val description = R.string.backend_fb2
    override val richText = true
    override val chaptered = true
    override val positionUnit = PositionUnit.CHAPTER

    override fun source(context: Context, uri: Uri, name: String, cache: DocumentCache?): TextSource =
        Fb2Source { context.contentResolver.openInputStream(uri) ?: error("Unable to open $name") }

    override fun source(file: File, name: String, context: Context?): TextSource = Fb2Source { file.inputStream() }

    override fun readText(input: InputStream, name: String): String =
        Fb2Book.parse(input).chapters.joinToString("\n\n") { it.text }
}

internal class Fb2Chapter(val title: String, val text: String, val imageIds: List<String>)

/** Parsed book: chapters with text and image ids, and decoded-on-demand binaries. */
internal class Fb2Book(val chapters: List<Fb2Chapter>, private val binaries: Map<String, String>) {
    fun image(id: String): ByteArray? = binaries[id]?.let { Base64.decode(it, Base64.DEFAULT) }

    companion object {
        fun parse(input: InputStream): Fb2Book {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val root = factory.newDocumentBuilder().parse(input).documentElement
            val bodies = root.children("body")
            // The main body has no name attribute; others are notes/comments.
            val body = bodies.firstOrNull { !it.hasAttribute("name") } ?: bodies.firstOrNull()
            val sections = body?.children("section").orEmpty()
            val chapters = if (sections.isEmpty()) {
                body?.let { listOf(chapter(it, "1")) }.orEmpty()
            } else {
                sections.mapIndexed { index, section -> chapter(section, "${index + 1}") }
            }
            val binaries = root.children("binary").associate { it.getAttribute("id") to it.textContent }
            return Fb2Book(chapters, binaries)
        }

        private fun chapter(element: Element, fallbackTitle: String): Fb2Chapter {
            val title = element.children("title").firstOrNull()?.textContent?.trim()?.replace(Regex("\\s+"), " ")
            val text = StringBuilder()
            val images = mutableListOf<String>()
            collect(element, text, images)
            return Fb2Chapter(title?.ifBlank { null } ?: fallbackTitle, text.toString().trim(), images)
        }

        /** Paragraph-like elements become lines; `<image>` hrefs are collected in order. */
        private fun collect(node: Node, text: StringBuilder, images: MutableList<String>) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                when (child.localName) {
                    "image" -> {
                        val href = (0 until child.attributes.length).map { child.attributes.item(it) }
                            .firstOrNull { it.localName == "href" }?.nodeValue
                        href?.removePrefix("#")?.let(images::add)
                    }
                    "p", "v", "subtitle", "text-author" -> text.append(child.textContent.trim()).append('\n')
                    "empty-line" -> text.append('\n')
                    else -> collect(child, text, images)
                }
            }
        }

        private fun Element.children(name: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName == name }
        }
    }
}

/** Parses the file once and serves chapters from the parsed book. */
private class Fb2Source(private val open: () -> InputStream) : TextSource {
    override val chaptered = true
    private val book by lazy { open().use(Fb2Book::parse) }

    override fun readPart(startOffset: Int, maxChars: Int): SourcePart? = null

    override fun chapters(): List<TextChapter> =
        book.chapters.mapIndexed { index, chapter -> TextChapter(index, chapter.title, 0) }

    override fun chapterSource(index: Int): TextSource {
        val chapter = book.chapters.getOrNull(index) ?: return Fb2ChapterSource("", emptyList(), book)
        return Fb2ChapterSource(chapter.text, chapter.imageIds, book)
    }
}

/** One chapter: text, plus one image per text chunk like EPUB chapters. */
private class Fb2ChapterSource(text: String, private val imageIds: List<String>, private val book: Fb2Book) : TextSource {
    private val content = StringTextSource(text)

    override fun readPart(startOffset: Int, maxChars: Int) = content.readPart(startOffset, maxChars)

    override fun imageCount(): Int = imageIds.size

    override fun imagesAt(offset: Int, pageSize: Int): List<ReadingImage> {
        val index = offset / pageSize
        val bytes = imageIds.getOrNull(index)?.let(book::image) ?: return emptyList()
        return listOf(ReadingImage("fb2-$index", bytes, "", ""))
    }
}
