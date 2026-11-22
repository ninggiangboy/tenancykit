// The design system's tokens switch to dark on the `dark` class; Keycloak's own dark mode sets `pf-v5-theme-dark`.
// Mirroring one onto the other keeps a single source for when the page is dark.
(function () {
  var root = document.documentElement;
  var query = window.matchMedia("(prefers-color-scheme: dark)");
  function sync() {
    root.classList.toggle("dark", query.matches);
  }
  sync();
  query.addEventListener("change", sync);
})();
