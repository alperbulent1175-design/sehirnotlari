package com.sehirnotlari.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File

/** Runs the real assets/release-notes.js under Rhino against a tiny DOM stand-in. */
class ReleaseNotesScriptTest {
    private lateinit var cx: Context
    private lateinit var scope: Scriptable

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, asset("release-notes.js").readText(), "release-notes.js", 1, null)
        // innerHTML / outerHTML / href / src / on* throw: the renderer must never touch them.
        js(
            """
            function El(tag) { this.tagName = tag; this.childNodes = []; this.className = ''; this._text = ''; }
            Object.defineProperty(El.prototype, 'firstChild', { get: function () { return this.childNodes[0] || null; } });
            Object.defineProperty(El.prototype, 'textContent', {
              get: function () { return this._text + this.childNodes.map(function (c) { return c.textContent; }).join(''); },
              set: function (v) { this.childNodes = []; this._text = String(v); }
            });
            ['innerHTML', 'outerHTML', 'href', 'src', 'onclick', 'onerror'].forEach(function (p) {
              Object.defineProperty(El.prototype, p, { get: function () { return ''; }, set: function () { throw new Error('forbidden ' + p); } });
            });
            El.prototype.appendChild = function (c) { this.childNodes.push(c); return c; };
            El.prototype.removeChild = function (c) { this.childNodes.splice(this.childNodes.indexOf(c), 1); return c; };
            El.prototype.setAttribute = function () { throw new Error('forbidden setAttribute'); };
            var doc = {
              created: [],
              createElement: function (t) { var e = new El(t.toLowerCase()); this.created.push(e.tagName); return e; },
              createTextNode: function (t) { return { tagName: '#text', textContent: String(t) }; }
            };
            function tags(el) { return el.childNodes.map(function (c) { return c.tagName; }).join(','); }
            """
        )
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun js(source: String): Any? = cx.evaluateString(scope, source, "test", 1, null)
    private fun str(source: String) = Context.toString(js(source))
    private fun bool(source: String) = Context.toBoolean(js(source))
    private fun num(source: String) = Context.toNumber(js(source)).toInt()

    @Test
    fun emptyOrNonTextNotesShowAFriendlyLine() {
        for (input in listOf("''", "null", "undefined", "42", "'   \\n\\n  '", "{}")) {
            assertTrue(input, bool("ReleaseNotes.parse($input).empty"))
        }
        js("var box = doc.createElement('div'); ReleaseNotes.render(doc, box, null)")
        assertEquals("Bu sürüm için not eklenmemiş.", str("box.textContent"))
    }

    @Test
    fun markdownBecomesHeadingsListsAndInlineStyles() {
        js(
            """
            var box = doc.createElement('div');
            ReleaseNotes.render(doc, box, '## Şehir Notları v1.4\n\n- **Neler Yeni?** ekranı\n- `SHA-256` doğrulaması\n\n1. bir\n2. iki\n\n> not\n\n---\nSon *satır*.');
            """
        )
        assertEquals("h3,ul,ol,blockquote,hr,p", str("tags(box)"))
        assertEquals("strong,#text", str("tags(box.childNodes[1].childNodes[0])"))
        assertEquals("code,#text", str("tags(box.childNodes[1].childNodes[1])"))
        assertTrue(str("box.textContent").contains("Neler Yeni? ekranı"))
        assertEquals("APK SHA-256: x\nİmza: y", str("ReleaseNotes.parse('APK SHA-256: x\\nİmza: y').blocks[0].spans[0].v"))
    }

    @Test
    fun htmlAndScriptInNotesAreNeverInterpreted() {
        js(
            """
            var box = doc.createElement('div');
            ReleaseNotes.render(doc, box, '<script>alert(1)</script>\n<img src=x onerror="alert(2)">Merhaba <b>kalın</b>\n<!-- gizli -->\n<a href="javascript:alert(3)">tık</a>');
            """
        )
        val text = str("box.textContent")
        assertFalse(text.contains("<"))
        assertFalse(text.contains("gizli"))
        assertTrue(text.contains("Merhaba kalın"))
        assertFalse(str("doc.created.join(',')").contains("script"))
        assertFalse(str("doc.created.join(',')").contains("img"))
        assertFalse(str("doc.created.join(',')").contains("a,"))
    }

    @Test
    fun linksAreShownAsTextAndUnsafeSchemesAreDropped() {
        js("var p = ReleaseNotes.parse('[Sürüm](https://github.com/x) ve [kötü](javascript:alert) ve [veri](data:text/html,x)')")
        assertEquals("Sürüm (https://github.com/x) ve kötü ve veri", str("p.blocks[0].spans.map(function(s){return s.v}).join('')"))
        js("var box = doc.createElement('div'); ReleaseNotes.render(doc, box, '[Sürüm](https://github.com/x)')")
        assertFalse(str("doc.created.join(',')").split(",").contains("a"))
    }

    @Test
    fun brokenMarkdownDoesNotCrashOrLoseText() {
        js("var p = ReleaseNotes.parse('**kapanmamış kalın\\n```\\nkod bloğu kapanmadı\\n')")
        assertEquals(2, num("p.blocks.length"))
        assertEquals("pre", str("p.blocks[1].type"))
        js("var q = ReleaseNotes.parse('[eksik bağlantı(https://x\\n# \\n####### yedi')")
        assertFalse(bool("q.empty"))
    }

    @Test
    fun hugeNotesAreCutAndSaySo() {
        js("var big = ''; for (var i = 0; i < 5000; i++) big += '- madde ' + i + '\\n';")
        assertTrue(bool("ReleaseNotes.parse(big).truncated"))
        js("var box = doc.createElement('div'); ReleaseNotes.render(doc, box, big)")
        assertTrue(str("box.textContent").contains("GitHub sürüm sayfasındadır"))
        js("var many = ''; for (var j = 0; j < 400; j++) many += 'paragraf ' + j + '\\n\\n';")
        assertTrue(bool("ReleaseNotes.parse(many).truncated"))
    }

    @Test
    fun scriptAvoidsHtmlSinks() {
        val source = asset("release-notes.js").readText()
        for (sink in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "Function(", "setAttribute")) {
            val code = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse(sink, code.contains(sink))
        }
    }

    private fun asset(name: String): File {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }
    }
}
