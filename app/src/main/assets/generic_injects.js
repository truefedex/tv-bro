// context menu support
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
    window.TVBRO_touchStartX = e.touches[0].clientX;
    window.TVBRO_touchStartY = e.touches[0].clientY;
});
// the virtual cursor is a mouse for pages in the desktop view: its long press touches nothing
window.addEventListener("mousemove", function(e) {
    window.TVBRO_activeElement = e.target;
}, {capture: true, passive: true});