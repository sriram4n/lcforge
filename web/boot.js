// Runs before the first paint: loaded with a plain, parser-blocking <script src> in <head>.
//
// Its only job is choosing the palette early enough that a remembered one does not arrive as a
// flash of the default on every page load. It lives in a file rather than inline in index.html
// because the page's Content-Security-Policy allows no inline script at all — that is what makes a
// script smuggled into a problem statement inert even if the sanitizer ever misses one.
//
// It repeats palette.js's resolution in miniature, because that module loads too late to prevent
// the flash. Keep the two in step. Nothing chosen yet means follow the OS: Forge by night, Paper
// by day.
(function () {
  try {
    var s = localStorage, p = s.getItem('lcforge.palette') || 'system';
    if (p === 'system') {
      var light = window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches;
      p = s.getItem(light ? 'lcforge.palette.light' : 'lcforge.palette.dark') || (light ? 'paper' : 'forge');
    }
    document.documentElement.dataset.palette = p;
  } catch (e) { /* private mode; the default palette is a fine place to land */ }
})();
