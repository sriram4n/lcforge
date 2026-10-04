// Paint the right palette before the first frame: the one you tried last time on this site, else
// light or dark to match your system. A file rather than an inline script because the page's
// security policy allows no inline script at all; it is tiny and blocking on purpose, since anything
// later would flash the wrong colours first.
(() => {
  const LIGHT = ['paper', 'github-light', 'solarized-light', 'catppuccin-latte', 'rose-pine-dawn'];
  let p = null;
  try { p = localStorage.getItem('lcforge.site.palette'); } catch { /* storage blocked: follow the system */ }
  if (!p) p = matchMedia('(prefers-color-scheme: light)').matches ? 'paper' : 'forge';
  const root = document.documentElement;
  root.dataset.palette = p;
  root.style.colorScheme = LIGHT.includes(p) ? 'light' : 'dark';
})();
