package com.phlox.tvwebbrowser.webengine

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorPageTemplateTest {
    @Test
    fun fillsHolesWithEscapedValues() {
        val page = ErrorPageTemplate.fill(
            """<h1>{{title}}</h1><button data-url="{{url}}">{{title}}</button>""",
            mapOf("title" to "Can't <reach>", "url" to "https://a.example/?q=\"x\"&y=1")
        )
        assertEquals(
            "<h1>Can&#39;t &lt;reach&gt;</h1>" +
                    "<button data-url=\"https://a.example/?q=&quot;x&quot;&amp;y=1\">Can&#39;t &lt;reach&gt;</button>",
            page
        )
    }

    @Test
    fun leavesHoleWithoutValueEmpty() {
        assertEquals("<dd></dd>", ErrorPageTemplate.fill("<dd>{{hostname}}</dd>", emptyMap()))
    }

    @Test
    fun keepsCssAndScriptBraces() {
        val template = "body { margin: 0; } function f() { return {a: 1}; }"
        assertEquals(template, ErrorPageTemplate.fill(template, mapOf("a" to "x")))
    }

    @Test
    fun doesNotFillHolesInsideValues() {
        assertEquals("{{title}}", ErrorPageTemplate.fill("{{message}}", mapOf("message" to "{{title}}", "title" to "x")))
    }
}
