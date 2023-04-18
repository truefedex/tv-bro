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
}