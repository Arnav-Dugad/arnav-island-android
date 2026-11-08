package io.github.arnavdugad.arnavisland.link

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Revision 13 (island 0.30), query 5: a PC browses this phone's files. Op 0 lists a folder (string path, "" for the
 * phone's places; u8 sort 0 name, 1 newest, 2 largest, 3 kind; u16 offset; u16 max, at most 200; u8 thumbs), answered
 * u8 state (1 listed, 2 off, 3 needs Android's permission, 4 not found) and, when listed, the PC's own folder layout
 * (IslandWire.files reads it). Op 1 sends a file or a folder to the PC (string path, u32 ask), answered u8 state; the
 * transfer carries the PC's ask, so the PC takes it without asking. Plain JVM code: the unit tests run it on real folders.
 */
object FilesWire {
    const val LIST = 0; const val SEND = 1
    const val LISTED = 1; const val OFF = 2; const val PERMISSION = 3; const val NOT_FOUND = 4
    const val FILE = 0; const val FOLDER = 1; const val VOLUME = 2; const val PLACE = 3
    const val MAX = 200; const val THUMB_LIMIT = 8 * 1024

    sealed interface Request
    class Listing(val path: String, val sort: Int, val offset: Int, val max: Int, val thumbs: Boolean) : Request
    class Send(val path: String, val ask: Int) : Request

    /** A question's payload; null when it's malformed. */
    fun request(payload: ByteArray): Request? {
        val r = Reader(payload)
        return when (r.u8()) {
            LIST -> { val path = r.string(4096) ?: return null; val sort = r.u8() ?: return null; val offset = r.u16() ?: return null; val max = r.u16() ?: return null; val thumbs = r.u8() ?: return null
                Listing(path, sort.coerceIn(0, 3), offset, max.coerceIn(1, MAX), thumbs != 0) }
            SEND -> { val path = r.string(4096) ?: return null; val ask = (r.u32() ?: return null).toInt(); if (ask == 0) return null; Send(path, ask) }
            else -> null
        }
    }
    fun listRequest(path: String, sort: Int = 0, offset: Int = 0, max: Int = MAX, thumbs: Boolean = false) =
        Bytes().u8(LIST).string(path).u8(sort).u16(offset).u16(max).u8(if (thumbs) 1 else 0).build()
    fun sendRequest(path: String, ask: Int) = Bytes().u8(SEND).string(path).u32(ask).build()

    class Entry(val name: String, val path: String, val kind: Int, val size: Long, val modified: Long, var thumb: ByteArray? = null)
    class Page(val path: String, val title: String, val parent: String, val total: Int, val entries: List<Entry>)

    fun state(s: Int) = byteArrayOf(s.toByte())
    /** A page as the PC reads it: state 1, then the PC's own folder layout. Thumbnails past the answer's budget are left out. */
    fun listed(p: Page): ByteArray {
        val b = Bytes().u8(LISTED).string(p.path).string(p.title).string(p.parent).u32(p.total).u16(p.entries.size)
        var budget = Proto.ANSWER_BUDGET - 4096 - p.entries.sumOf { it.name.length * 3 + it.path.length * 3 + 40 }
        p.entries.forEach { e ->
            val t = e.thumb?.takeIf { it.size <= THUMB_LIMIT && it.size <= budget }; if (t != null) budget -= t.size
            b.string(e.name).string(e.path).u8(e.kind).u64(e.size.coerceAtLeast(0)).u64(e.modified.coerceAtLeast(0)).blob(t ?: ByteArray(0))
        }
        return b.build()
    }

    /** A folder that is a starting point: the phone's own storage and its well-known folders (places), other volumes. */
    class Root(val dir: File, val title: String, val kind: Int)

    /**
     * Lists and sends files under [roots] only (the phone's external storage), never following a symbolic link and
     * leaving out hidden files. [places]: the phone's places in order (those that exist are listed); [thumb]: a
     * picture's thumbnail (a JPEG within 8 KB), made only when asked, at most 48 a page and within a second and a half.
     */
    class Lister(private val roots: () -> List<Root>, private val places: () -> List<Root>, private val thumb: (File) -> ByteArray? = { null }) {
        fun answer(r: Listing): ByteArray {
            if (r.path.isEmpty()) return listed(placesPage(r))
            val dir = resolve(r.path) ?: return state(NOT_FOUND)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return state(NOT_FOUND)
            return listed(folderPage(dir, r))
        }

        private fun placesPage(r: Listing): Page {
            val all = (places() + roots().filter { it.kind == VOLUME }).filter { Files.isDirectory(it.dir.toPath(), LinkOption.NOFOLLOW_LINKS) }
            val entries = all.map { Entry(it.title, it.dir.path, it.kind, if (it.kind == VOLUME) runCatching { it.dir.freeSpace }.getOrDefault(0L) else 0L, it.dir.lastModified() / 1000) }
            return Page("", "This phone", "", entries.size, entries.drop(r.offset).take(r.max))
        }

        private fun folderPage(dir: Path, r: Listing): Page {
            val root = rootOf(dir)!!
            val all = ArrayList<Entry>()
            runCatching {
                Files.newDirectoryStream(dir).use { stream ->
                    for (p in stream) {
                        val name = p.fileName?.toString() ?: continue
                        if (name.startsWith(".") || Files.isSymbolicLink(p)) continue
                        val folder = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        if (!folder && !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue
                        val f = p.toFile()
                        all += Entry(name, p.toString(), if (folder) FOLDER else FILE, if (folder) 0L else f.length(), f.lastModified() / 1000)
                        if (all.size >= 20_000) break
                    }
                }
            }
            sort(all, r.sort)
            val page = all.drop(r.offset).take(r.max)
            if (r.thumbs) {
                val until = System.currentTimeMillis() + 1_500; var made = 0
                for (e in page) {
                    if (made >= 48 || System.currentTimeMillis() > until) break
                    if (e.kind == FILE && picture(e.name)) { e.thumb = runCatching { thumb(File(e.path)) }.getOrNull()?.takeIf { it.size <= THUMB_LIMIT }; made++ }
                }
            }
            val atRoot = dir == root.dir.toPath()
            val title = if (atRoot) root.title else dir.fileName?.toString() ?: root.title
            val parent = if (atRoot) "" else dir.parent?.toString() ?: ""
            return Page(dir.toString(), title, parent, all.size, page)
        }

        /**
         * What to send for a path: its title, whether it's a folder, and its files (a folder's whole tree, as
         * "Folder/inner/file"); null when it isn't there (or isn't allowed).
         */
        fun sources(path: String): Triple<String, Boolean, List<Source>>? {
            val p = resolve(path) ?: return null
            val name = p.fileName?.toString() ?: return null
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { val f = p.toFile(); return Triple(name, false, listOf(Source(safeName(name), f.length()) { FileInputStream(f) })) }
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return null
            val top = safeName(name); val out = ArrayList<Source>(); var total = 0L
            fun walk(dir: Path, rel: String, depth: Int) {
                if (depth > 24 || out.size >= Proto.MAX_FILES) return
                val children = runCatching { Files.newDirectoryStream(dir).use { s -> s.toList() } }.getOrDefault(emptyList()).sortedBy { it.fileName.toString().lowercase() }
                for (c in children) {
                    val n = c.fileName?.toString() ?: continue
                    if (n.startsWith(".") || Files.isSymbolicLink(c)) continue
                    if (Files.isDirectory(c, LinkOption.NOFOLLOW_LINKS)) walk(c, "$rel/${safeName(n)}", depth + 1)
                    else if (Files.isRegularFile(c, LinkOption.NOFOLLOW_LINKS)) {
                        val f = c.toFile(); val size = f.length()
                        if (out.size >= Proto.MAX_FILES || size > Proto.MAX_FILE || total + size > Proto.MAX_TOTAL) return
                        total += size; out += Source("$rel/${safeName(n)}", size) { FileInputStream(f) }
                    }
                }
            }
            walk(p, top, 0)
            return if (out.isEmpty()) null else Triple(name, true, out)
        }

        /**
         * A path the PC sent, made safe: absolute, normal (no "." or ".."), under one of [roots], with no hidden part and no
         * symbolic link from the root down. Null when it's anything else.
         */
        fun resolve(path: String): Path? {
            if (path.isEmpty() || path.length > 4096 || path.any { it.code < 32 }) return null
            val p = runCatching { Paths.get(path) }.getOrNull() ?: return null
            if (!p.isAbsolute || p.any { it.toString() == ".." || it.toString() == "." }) return null
            val n = p.normalize()
            val root = rootOf(n) ?: return null
            val base = root.dir.toPath().normalize()
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return null
            var at = base
            for (part in base.relativize(n)) {
                if (part.toString().startsWith(".")) return null
                at = at.resolve(part)
                if (Files.isSymbolicLink(at) || !Files.exists(at, LinkOption.NOFOLLOW_LINKS)) return null
            }
            return n
        }
        private fun rootOf(p: Path): Root? = roots().filter { p.startsWith(it.dir.toPath().normalize()) }.maxByOrNull { it.dir.path.length }
    }

    /** Kinds first (places, volumes, folders, files), then by the sort asked for, then by name. */
    fun sort(list: MutableList<Entry>, sort: Int) {
        fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
        list.sortWith(Comparator { a, b ->
            if (a.kind != b.kind) return@Comparator b.kind - a.kind
            when {
                sort == 1 && a.modified != b.modified -> return@Comparator b.modified.compareTo(a.modified)
                sort == 2 && a.size != b.size -> return@Comparator b.size.compareTo(a.size)
                sort == 3 && ext(a.name) != ext(b.name) -> return@Comparator ext(a.name).compareTo(ext(b.name))
            }
            a.name.compareTo(b.name, ignoreCase = true)
        })
    }
    private val pictures = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")
    fun picture(name: String) = name.substringAfterLast('.', "").lowercase() in pictures
}
