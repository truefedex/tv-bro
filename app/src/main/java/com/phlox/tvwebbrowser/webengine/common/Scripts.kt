package com.phlox.tvwebbrowser.webengine.common

object Scripts {
    val INITIAL_SCRIPT = """
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
});"""

    val LONG_PRESS_SCRIPT = """
var element = window.TVBRO_activeElement;
if (element != null) {
  if ('A' == element.tagName) {
    element.protocol+'//'+element.host+element.pathname+element.search+element.hash;
  }
}"""

    val SCROLL_BY_SCRIPT = """
var element = document.elementFromPoint(%d, %d);
var style = getComputedStyle(element);
var excludeStaticParent = style.position === "absolute";
var overflowRegex = includeHidden ? /(auto|scroll|hidden)/ : /(auto|scroll)/;
var scrollable;

if (style.position != "fixed") {
	for (var parent = element; (parent = parent.parentElement);) {
		style = getComputedStyle(parent);
		if (excludeStaticParent && style.position === "static") {
			continue;
		}
		if (overflowRegex.test(style.overflow + style.overflowY + style.overflowX)) {
			scrollable = parent;
			break;
		}
	}
}

if (!scrollable) {
	scrollable = document.body;
}

scrollable.scrollBy(%d, %d);"""
}