/* @ds-bundle: {"format":4,"namespace":"Suite","components":[{"name":"Button"},{"name":"Field"},{"name":"Checkbox"},{"name":"Switch"},{"name":"StatusBadge"},{"name":"AppMark"},{"name":"Money"},{"name":"CopyId"},{"name":"Kbd"},{"name":"Tabs"},{"name":"Card"},{"name":"Stat"},{"name":"Alert"},{"name":"FilterChip"},{"name":"FacetedFilter"},{"name":"DataTable"},{"name":"BulkBar"},{"name":"DropdownMenu"},{"name":"Tooltip"},{"name":"Dialog"},{"name":"Sheet"},{"name":"Toaster"},{"name":"CommandPalette"},{"name":"Skeleton"},{"name":"EmptyState"},{"name":"Icon"}]} */
(function () {
  var React = window.React;
  var h = React.createElement;
  var useState = React.useState, useEffect = React.useEffect, useRef = React.useRef;

  function cx() { return Array.prototype.filter.call(arguments, Boolean).join(' '); }
  function omit(obj, keys) { var out = {}; for (var k in obj) { if (Object.prototype.hasOwnProperty.call(obj, k) && keys.indexOf(k) < 0) out[k] = obj[k]; } return out; }
  function useControllable(value, initial, onChange) {
    var st = useState(initial);
    var controlled = value !== undefined;
    return [controlled ? value : st[0], function (next) { if (!controlled) st[1](next); if (onChange) onChange(next); }];
  }
  /* Closes on Escape and on a pointer press outside `ref`. */
  function useDismiss(open, ref, close) {
    useEffect(function () {
      if (!open) return undefined;
      function onKey(e) { if (e.key === 'Escape') close(); }
      function onDown(e) { if (ref.current && !ref.current.contains(e.target)) close(); }
      document.addEventListener('keydown', onKey);
      document.addEventListener('mousedown', onDown);
      return function () { document.removeEventListener('keydown', onKey); document.removeEventListener('mousedown', onDown); };
    }, [open]);
  }
  /* Moves focus into a surface when it opens and back to the opener when it closes. */
  function useFocusReturn(open, ref) {
    useEffect(function () {
      if (!open) return undefined;
      var opener = document.activeElement;
      var el = ref.current && ref.current.querySelector('[data-autofocus], input, button, [tabindex]');
      if (el && el.focus) el.focus();
      return function () { if (opener && opener.focus) opener.focus(); };
    }, [open]);
  }
  /* True while the media query matches; phones are below bp-tablet (768px). */
  function useMedia(query) {
    var mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query) : null;
    var st = useState(mq ? mq.matches : false);
    useEffect(function () {
      if (!mq) return undefined;
      function on() { st[1](mq.matches); }
      mq.addEventListener('change', on); on();
      return function () { mq.removeEventListener('change', on); };
    }, [query]);
    return st[0];
  }
  var PHONE = '(max-width: 767.98px)';
  /* Drag a bottom sheet down by its handle or head to close it: past 25% of its height or flicked faster than 0.5px/ms. */
  function useDragDismiss(panelRef, onClose, enabled) {
    var drag = useRef(null);
    function down(e) {
      if (!enabled || e.button > 0 || e.target.closest('button, a, input, select, textarea')) return;
      drag.current = { y: e.clientY, t: Date.now(), dy: 0 };
      e.currentTarget.setPointerCapture(e.pointerId);
      if (panelRef.current) panelRef.current.classList.add('sc-dragging');
    }
    function move(e) {
      if (!drag.current || !panelRef.current) return;
      drag.current.dy = Math.max(0, e.clientY - drag.current.y);
      panelRef.current.style.transform = 'translateY(' + drag.current.dy + 'px)';
    }
    function up() {
      var d = drag.current, el = panelRef.current; drag.current = null;
      if (!d || !el) return;
      el.classList.remove('sc-dragging');
      var fast = d.dy / Math.max(1, Date.now() - d.t) > 0.5;
      if (d.dy > el.offsetHeight * 0.25 || (fast && d.dy > 24)) { onClose && onClose(); }
      el.style.transition = 'transform var(--duration-base) var(--ease-out)'; el.style.transform = '';
      setTimeout(function () { if (el) el.style.transition = ''; }, 200);
    }
    return { onPointerDown: down, onPointerMove: move, onPointerUp: up, onPointerCancel: up };
  }
  /* useHotkey('mod+k', fn): mod is Cmd on macOS, Ctrl elsewhere. Ignores keys typed into fields unless allowInFields. */
  function useHotkey(combo, handler, allowInFields) {
    useEffect(function () {
      var parts = combo.toLowerCase().split('+'), key = parts.pop();
      function onKey(e) {
        var t = e.target, typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
        if (typing && !allowInFields) return;
        var mod = parts.indexOf('mod') >= 0;
        if (mod !== (e.metaKey || e.ctrlKey)) return;
        if (parts.indexOf('shift') >= 0 !== e.shiftKey) return;
        if (e.key.toLowerCase() === key) { e.preventDefault(); handler(e); }
      }
      document.addEventListener('keydown', onKey);
      return function () { document.removeEventListener('keydown', onKey); };
    }, [combo, handler]);
  }

  /* Icon geometry from Lucide 0.460 (ISC licence, lucide.dev), the icon set shadcn uses. 24px grid, 2px stroke. */
  var ICONS = {"plus":[["path",{"d":"M5 12h14"}],["path",{"d":"M12 5v14"}]],"plus-circle":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"M8 12h8"}],["path",{"d":"M12 8v8"}]],"x":[["path",{"d":"M18 6 6 18"}],["path",{"d":"m6 6 12 12"}]],"check":[["path",{"d":"M20 6 9 17l-5-5"}]],"search":[["circle",{"cx":"11","cy":"11","r":"8"}],["path",{"d":"m21 21-4.3-4.3"}]],"chevron-down":[["path",{"d":"m6 9 6 6 6-6"}]],"chevron-up":[["path",{"d":"m18 15-6-6-6 6"}]],"chevron-right":[["path",{"d":"m9 18 6-6-6-6"}]],"chevron-left":[["path",{"d":"m15 18-6-6 6-6"}]],"chevrons-up-down":[["path",{"d":"m7 15 5 5 5-5"}],["path",{"d":"m7 9 5-5 5 5"}]],"arrow-up":[["path",{"d":"m5 12 7-7 7 7"}],["path",{"d":"M12 19V5"}]],"arrow-down":[["path",{"d":"M12 5v14"}],["path",{"d":"m19 12-7 7-7-7"}]],"arrow-up-down":[["path",{"d":"m21 16-4 4-4-4"}],["path",{"d":"M17 20V4"}],["path",{"d":"m3 8 4-4 4 4"}],["path",{"d":"M7 4v16"}]],"arrow-up-right":[["path",{"d":"M7 7h10v10"}],["path",{"d":"M7 17 17 7"}]],"arrow-down-right":[["path",{"d":"m7 7 10 10"}],["path",{"d":"M17 7v10H7"}]],"alert":[["path",{"d":"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"}],["path",{"d":"M12 9v4"}],["path",{"d":"M12 17h.01"}]],"info":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"M12 16v-4"}],["path",{"d":"M12 8h.01"}]],"ban":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"m4.9 4.9 14.2 14.2"}]],"check-circle":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"m9 12 2 2 4-4"}]],"x-circle":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"m15 9-6 6"}],["path",{"d":"m9 9 6 6"}]],"clock":[["circle",{"cx":"12","cy":"12","r":"10"}],["polyline",{"points":"12 6 12 12 16 14"}]],"key":[["path",{"d":"M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z"}],["circle",{"cx":"16.5","cy":"7.5","r":".5","fill":"currentColor"}]],"copy":[["rect",{"width":"14","height":"14","x":"8","y":"8","rx":"2","ry":"2"}],["path",{"d":"M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"}]],"refresh":[["path",{"d":"M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}],["path",{"d":"M21 3v5h-5"}],["path",{"d":"M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}],["path",{"d":"M8 16H3v5"}]],"more":[["circle",{"cx":"12","cy":"12","r":"1"}],["circle",{"cx":"19","cy":"12","r":"1"}],["circle",{"cx":"5","cy":"12","r":"1"}]],"download":[["path",{"d":"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}],["polyline",{"points":"7 10 12 15 17 10"}],["line",{"x1":"12","x2":"12","y1":"15","y2":"3"}]],"external":[["path",{"d":"M15 3h6v6"}],["path",{"d":"M10 14 21 3"}],["path",{"d":"M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]],"shield":[["path",{"d":"M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"}]],"grid":[["rect",{"width":"7","height":"7","x":"3","y":"3","rx":"1"}],["rect",{"width":"7","height":"7","x":"14","y":"3","rx":"1"}],["rect",{"width":"7","height":"7","x":"14","y":"14","rx":"1"}],["rect",{"width":"7","height":"7","x":"3","y":"14","rx":"1"}]],"users":[["path",{"d":"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"}],["circle",{"cx":"9","cy":"7","r":"4"}],["path",{"d":"M22 21v-2a4 4 0 0 0-3-3.87"}],["path",{"d":"M16 3.13a4 4 0 0 1 0 7.75"}]],"settings":[["path",{"d":"M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"}],["circle",{"cx":"12","cy":"12","r":"3"}]],"panel-left":[["rect",{"width":"18","height":"18","x":"3","y":"3","rx":"2"}],["path",{"d":"M9 3v18"}]],"bell":[["path",{"d":"M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"}],["path",{"d":"M10.3 21a1.94 1.94 0 0 0 3.4 0"}]],"file":[["path",{"d":"M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"}],["path",{"d":"M14 2v4a2 2 0 0 0 2 2h4"}],["path",{"d":"M10 9H8"}],["path",{"d":"M16 13H8"}],["path",{"d":"M16 17H8"}]],"inbox":[["polyline",{"points":"22 12 16 12 14 15 10 15 8 12 2 12"}],["path",{"d":"M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"}]],"receipt":[["path",{"d":"M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z"}],["path",{"d":"M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8"}],["path",{"d":"M12 17.5v-11"}]],"wallet":[["path",{"d":"M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"}],["path",{"d":"M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"}]],"credit-card":[["rect",{"width":"20","height":"14","x":"2","y":"5","rx":"2"}],["line",{"x1":"2","x2":"22","y1":"10","y2":"10"}]],"repeat":[["path",{"d":"m17 2 4 4-4 4"}],["path",{"d":"M3 11v-1a4 4 0 0 1 4-4h14"}],["path",{"d":"m7 22-4-4 4-4"}],["path",{"d":"M21 13v1a4 4 0 0 1-4 4H3"}]],"ticket":[["path",{"d":"M2 9a3 3 0 1 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 1 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z"}],["path",{"d":"M9 9h.01"}],["path",{"d":"m15 9-6 6"}],["path",{"d":"M15 15h.01"}]],"webhook":[["path",{"d":"M18 16.98h-5.99c-1.1 0-1.95.94-2.48 1.9A4 4 0 0 1 2 17c.01-.7.2-1.4.57-2"}],["path",{"d":"m6 17 3.13-5.78c.53-.97.1-2.18-.5-3.1a4 4 0 1 1 6.89-4.06"}],["path",{"d":"m12 6 3.13 5.73C15.66 12.7 16.9 13 18 13a4 4 0 0 1 0 8"}]],"layout-dashboard":[["rect",{"width":"7","height":"9","x":"3","y":"3","rx":"1"}],["rect",{"width":"7","height":"5","x":"14","y":"3","rx":"1"}],["rect",{"width":"7","height":"9","x":"14","y":"12","rx":"1"}],["rect",{"width":"7","height":"5","x":"3","y":"16","rx":"1"}]],"columns":[["rect",{"width":"18","height":"18","x":"3","y":"3","rx":"2"}],["path",{"d":"M9 3v18"}],["path",{"d":"M15 3v18"}]],"sliders":[["line",{"x1":"21","x2":"14","y1":"4","y2":"4"}],["line",{"x1":"10","x2":"3","y1":"4","y2":"4"}],["line",{"x1":"21","x2":"12","y1":"12","y2":"12"}],["line",{"x1":"8","x2":"3","y1":"12","y2":"12"}],["line",{"x1":"21","x2":"16","y1":"20","y2":"20"}],["line",{"x1":"12","x2":"3","y1":"20","y2":"20"}],["line",{"x1":"14","x2":"14","y1":"2","y2":"6"}],["line",{"x1":"8","x2":"8","y1":"10","y2":"14"}],["line",{"x1":"16","x2":"16","y1":"18","y2":"22"}]],"log-out":[["path",{"d":"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}],["polyline",{"points":"16 17 21 12 16 7"}],["line",{"x1":"21","x2":"9","y1":"12","y2":"12"}]],"undo":[["path",{"d":"M9 14 4 9l5-5"}],["path",{"d":"M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"}]],"trash":[["path",{"d":"M3 6h18"}],["path",{"d":"M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"}],["path",{"d":"M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"}],["line",{"x1":"10","x2":"10","y1":"11","y2":"17"}],["line",{"x1":"14","x2":"14","y1":"11","y2":"17"}]],"mail":[["rect",{"width":"20","height":"16","x":"2","y":"4","rx":"2"}],["path",{"d":"m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"}]],"loader":[["path",{"d":"M21 12a9 9 0 1 1-6.219-8.56"}]],"building":[["path",{"d":"M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"}],["path",{"d":"M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"}],["path",{"d":"M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"}],["path",{"d":"M10 6h4"}],["path",{"d":"M10 10h4"}],["path",{"d":"M10 14h4"}],["path",{"d":"M10 18h4"}]],"clipboard":[["rect",{"width":"8","height":"4","x":"8","y":"2","rx":"1","ry":"1"}],["path",{"d":"M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"}],["path",{"d":"M12 11h4"}],["path",{"d":"M12 16h4"}],["path",{"d":"M8 11h.01"}],["path",{"d":"M8 16h.01"}]],"pointer":[["path",{"d":"M14 4.1 12 6"}],["path",{"d":"m5.1 8-2.9-.8"}],["path",{"d":"m6 12-1.9 2"}],["path",{"d":"M7.2 2.2 8 5.1"}],["path",{"d":"M9.037 9.69a.498.498 0 0 1 .653-.653l11 4.5a.5.5 0 0 1-.074.949l-4.349 1.041a1 1 0 0 0-.74.739l-1.04 4.35a.5.5 0 0 1-.95.074z"}]],"calendar":[["path",{"d":"M8 2v4"}],["path",{"d":"M16 2v4"}],["rect",{"width":"18","height":"18","x":"3","y":"4","rx":"2"}],["path",{"d":"M3 10h18"}],["path",{"d":"M8 14h.01"}],["path",{"d":"M12 14h.01"}],["path",{"d":"M16 14h.01"}],["path",{"d":"M8 18h.01"}],["path",{"d":"M12 18h.01"}],["path",{"d":"M16 18h.01"}]],"contact":[["path",{"d":"M16 2v2"}],["path",{"d":"M7 22v-2a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v2"}],["path",{"d":"M8 2v2"}],["circle",{"cx":"12","cy":"11","r":"3"}],["rect",{"x":"3","y":"4","width":"18","height":"18","rx":"2"}]],"gift":[["rect",{"x":"3","y":"8","width":"18","height":"4","rx":"1"}],["path",{"d":"M12 8v13"}],["path",{"d":"M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7"}],["path",{"d":"M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5"}]],"workflow":[["rect",{"width":"8","height":"8","x":"3","y":"3","rx":"2"}],["path",{"d":"M7 11v4a2 2 0 0 0 2 2h4"}],["rect",{"width":"8","height":"8","x":"13","y":"13","rx":"2"}]],"life-buoy":[["circle",{"cx":"12","cy":"12","r":"10"}],["path",{"d":"m4.93 4.93 4.24 4.24"}],["path",{"d":"m14.83 9.17 4.24-4.24"}],["path",{"d":"m14.83 14.83 4.24 4.24"}],["path",{"d":"m9.17 14.83-4.24 4.24"}],["circle",{"cx":"12","cy":"12","r":"4"}]],"send":[["path",{"d":"M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z"}],["path",{"d":"m21.854 2.147-10.94 10.939"}]],"filter":[["path",{"d":"M3 6h18"}],["path",{"d":"M7 12h10"}],["path",{"d":"M10 18h4"}]],"user":[["path",{"d":"M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"}],["circle",{"cx":"12","cy":"7","r":"4"}]],"command":[["path",{"d":"M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"}]],"sparkle":[["circle",{"cx":"12","cy":"12","r":"10"}],["circle",{"cx":"12","cy":"12","r":"1"}]]};
  function Icon(p) {
    var size = p.size || 16, name = p.name;
    var kids = name === 'dot' ? [h('circle', { key: 0, cx: 12, cy: 12, r: 5, fill: 'currentColor', stroke: 'none' })]
      : name === 'ring' ? [h('circle', { key: 0, cx: 12, cy: 12, r: 5 })]
        : (ICONS[name] || ICONS.info).map(function (n, i) { return h(n[0], Object.assign({ key: i }, n[1])); });
    return h('svg', {
      className: cx('sc-icon', name === 'loader' && 'sc-spin', p.className), width: size, height: size, viewBox: '0 0 24 24',
      fill: 'none', stroke: 'currentColor', strokeWidth: p.strokeWidth || 2, strokeLinecap: 'round', strokeLinejoin: 'round',
      'aria-hidden': p.label ? undefined : true, role: p.label ? 'img' : undefined, 'aria-label': p.label
    }, kids);
  }
  ICONS.dot = ICONS.ring = [];
  Icon.names = Object.keys(ICONS);

  function Kbd(p) {
    var keys = p.keys || [p.children];
    var isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '');
    return h('span', { className: 'sc-kbd-group', 'aria-label': p['aria-label'] }, keys.map(function (k, i) {
      var label = k === 'mod' ? (isMac ? '⌘' : 'Ctrl') : k === 'shift' ? '⇧' : k === 'enter' ? '↵' : k === 'up' ? '↑' : k === 'down' ? '↓' : k === 'esc' ? 'Esc' : k;
      return h('kbd', { key: i, className: 'sc-kbd' }, label);
    }));
  }

  function Button(p) {
    var variant = p.variant || 'default', size = p.size || 'md';
    var rest = omit(p, ['variant', 'size', 'icon', 'iconEnd', 'iconOnly', 'loading', 'shortcut', 'children', 'className']);
    var iconSize = size === 'sm' ? 14 : 16;
    return h('button', Object.assign({ type: 'button' }, rest, {
      className: cx('sc-btn', 'sc-btn--' + variant, 'sc-btn--' + size, p.iconOnly && 'sc-btn--icon', p.className),
      disabled: p.disabled || p.loading, 'aria-busy': p.loading ? true : undefined,
      'aria-label': p.iconOnly && typeof p.children === 'string' ? p.children : rest['aria-label']
    }),
      p.loading ? h(Icon, { name: 'loader', size: iconSize }) : p.icon && h(Icon, { name: p.icon, size: iconSize }),
      p.iconOnly ? null : p.children,
      p.iconEnd && !p.iconOnly && h(Icon, { name: p.iconEnd, size: iconSize }),
      p.shortcut && !p.iconOnly && h(Kbd, { keys: p.shortcut }));
  }

  function Field(p) {
    var autoId = React.useId();
    var id = p.id || autoId;
    var msgId = p.error ? id + '-err' : p.hint ? id + '-hint' : undefined;
    var rest = omit(p, ['label', 'hint', 'error', 'optional', 'type', 'options', 'prefix', 'suffix', 'icon', 'mono', 'size', 'id', 'className', 'hideLabel']);
    var common = Object.assign({}, rest, {
      id: id, 'aria-invalid': p.error ? true : undefined, 'aria-describedby': msgId,
      className: cx('sc-input', p.type === 'select' && 'sc-select', p.mono && 'sc-input--mono', p.size === 'sm' && 'sc-input--sm')
    });
    var control = p.type === 'select'
      ? h('select', common, (p.options || []).map(function (o) { var opt = typeof o === 'string' ? { value: o, label: o } : o; return h('option', { key: opt.value, value: opt.value }, opt.label); }))
      : p.type === 'textarea' ? h('textarea', common)
        : h('input', Object.assign({ type: p.type || 'text' }, common));
    if (p.prefix || p.suffix || p.icon) {
      control = h('div', { className: 'sc-input-group' },
        p.icon && h(Icon, { name: p.icon, size: 16 }),
        p.prefix && h('span', { className: 'sc-input-group__addon' }, p.prefix),
        control,
        p.suffix && h('span', { className: 'sc-input-group__addon' }, p.suffix));
    }
    return h('div', { className: cx('sc-field', p.className) },
      p.label && h('label', { htmlFor: id, className: cx('sc-label', p.hideLabel && 'sc-sr') }, p.label, p.optional && h('span', { className: 'sc-label__opt' }, 'Optional')),
      control,
      p.error ? h('p', { id: id + '-err', className: 'sc-help sc-help--error', role: 'alert' }, h(Icon, { name: 'alert', size: 14 }), p.error)
        : p.hint ? h('p', { id: id + '-hint', className: 'sc-help' }, p.hint) : null);
  }

  function Checkbox(p) {
    var autoId = React.useId(), id = p.id || autoId;
    var rest = omit(p, ['label', 'description', 'indeterminate', 'id', 'className']);
    var ref = useRef(null);
    useEffect(function () { if (ref.current) ref.current.indeterminate = !!p.indeterminate; }, [p.indeterminate]);
    var box = h('input', Object.assign({ type: 'checkbox', ref: ref, id: id, className: 'sc-check' }, rest));
    if (!p.label) return box;
    return h('label', { htmlFor: id, className: cx('sc-check-row', p.className) }, box,
      h('span', { className: 'sc-check-row__text' }, h('span', null, p.label), p.description && h('span', null, p.description)));
  }

  function Switch(p) {
    var autoId = React.useId(), id = p.id || autoId;
    var rest = omit(p, ['label', 'description', 'id', 'busy', 'className']);
    var sw = h('input', Object.assign({ type: 'checkbox', role: 'switch', id: id, className: 'sc-switch', 'aria-busy': p.busy ? true : undefined }, rest));
    if (!p.label) return sw;
    return h('div', { className: cx('sc-check-row', p.className), style: { alignItems: 'center', justifyContent: 'space-between' } },
      h('label', { htmlFor: id, className: 'sc-check-row__text', style: { cursor: 'pointer' } }, h('span', { style: { fontWeight: 500 } }, p.label), p.description && h('span', null, p.description)), sw);
  }

  var STATUS = {
    DRAFT: 'neutral', OPEN: 'info', PAID: 'success', VOID: 'neutral', UNCOLLECTIBLE: 'danger',
    ACTIVE: 'success', TRIALING: 'info', PAST_DUE: 'warning', CANCELED: 'neutral', ENDED: 'neutral',
    PENDING: 'warning', SUCCEEDED: 'success', FAILED: 'danger', REFUNDED: 'neutral',
    ENABLED: 'success', DISABLED: 'neutral', SUSPENDED: 'warning', REVOKED: 'danger', EXPIRED: 'neutral',
    DELIVERED: 'success', RETRYING: 'warning', DEAD: 'danger', RUNNING: 'info', COMPLETED: 'success', WAITING: 'warning'
  };
  function humanize(s) { var t = String(s).toLowerCase().replace(/_/g, ' '); return t.charAt(0).toUpperCase() + t.slice(1); }
  var TONE_ICON = { info: 'clock', success: 'check', warning: 'alert', danger: 'x-circle' };
  function StatusBadge(p) {
    var tone = p.tone || (p.status && STATUS[p.status]) || 'neutral';
    var text = p.children != null ? p.children : p.status ? humanize(p.status) : '';
    var icon = p.variant === 'plain' ? null : TONE_ICON[tone];
    return h('span', { className: cx('sc-badge', 'sc-badge--' + tone, p.variant === 'plain' && 'sc-badge--plain', p.className), title: p.status },
      icon && h(Icon, { name: icon, size: 12, strokeWidth: 2.5 }), text);
  }
  StatusBadge.tones = STATUS;
  StatusBadge.humanize = humanize;

  var APPS = {
    platform: ['building', 'Platform'], billing: ['receipt', 'Billing'], mailroom: ['mail', 'Mailroom'], notification: ['bell', 'Notification'],
    survey: ['clipboard', 'Survey'], attribution: ['pointer', 'Attribution'], booking: ['calendar', 'Booking'], customer: ['contact', 'Customer'],
    loyalty: ['gift', 'Loyalty'], workflow: ['workflow', 'Workflow'], helpdesk: ['life-buoy', 'Helpdesk']
  };
  function AppMark(p) {
    var app = APPS[p.app] || ['grid', p.app];
    var size = p.size === 'lg' ? 18 : p.size === 'sm' ? 12 : 14;
    return h('span', { className: cx('sc-appmark', 'sc-appmark--' + (p.size || 'md'), p.tone === 'neutral' && 'sc-appmark--neutral', p.className), 'data-app': APPS[p.app] ? p.app : undefined, title: p.withName ? undefined : app[1] },
      h('span', { className: 'sc-appmark__tile', 'aria-hidden': true }, h(Icon, { name: app[0], size: size })),
      p.withName ? h('span', { className: 'sc-appmark__name' }, p.name || app[1]) : h('span', { className: 'sc-sr' }, app[1]));
  }
  AppMark.apps = APPS;

  function fractionDigits(ccy) { try { return new Intl.NumberFormat('en', { style: 'currency', currency: ccy }).resolvedOptions().maximumFractionDigits; } catch (e) { return 2; } }
  function formatMoney(minor, currency, locale) {
    var ccy = (currency || 'USD').toUpperCase(), digits = fractionDigits(ccy);
    return new Intl.NumberFormat(locale || 'en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(Math.abs(Number(minor || 0)) / Math.pow(10, digits));
  }
  function Money(p) {
    var minor = Number(p.minor || 0), ccy = (p.currency || 'USD').toUpperCase();
    var sign = minor < 0 ? '−' : p.signed && minor > 0 ? '+' : '';
    return h('span', { className: cx('sc-money', minor < 0 && p.signed && 'sc-money--neg', p.className), title: minor + ' minor units' },
      sign + formatMoney(minor, ccy, p.locale), h('span', { className: 'sc-money__ccy' }, ccy));
  }
  Money.format = formatMoney;

  function CopyId(p) {
    var st = useState(false);
    function copy(e) {
      e.stopPropagation();
      var done = function () { st[1](true); setTimeout(function () { st[1](false); }, 1400); if (p.onCopy) p.onCopy(p.value); };
      try { navigator.clipboard.writeText(p.value).then(done, done); } catch (err) { done(); }
    }
    return h('button', { type: 'button', className: cx('sc-copy', p.mono && 'sc-copy--mono', p.className), onClick: copy, 'data-copied': st[0], 'aria-label': (st[0] ? 'Copied ' : 'Copy ') + p.value, title: st[0] ? 'Copied' : 'Copy' },
      h('span', null, p.children || p.value), h(Icon, { name: st[0] ? 'check' : 'copy', size: 14 }));
  }

  function Tabs(p) {
    var items = p.items || [];
    var s = useControllable(p.value, p.defaultValue || (items[0] && items[0].id), p.onChange);
    function onKey(e) {
      var i = items.findIndex(function (it) { return it.id === s[0]; });
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        e.preventDefault();
        var n = items[(i + (e.key === 'ArrowRight' ? 1 : items.length - 1)) % items.length];
        s[1](n.id);
        var btn = e.currentTarget.querySelector('[data-id="' + n.id + '"]'); if (btn) btn.focus();
      }
    }
    return h('div', { className: cx('sc-tabs', p.variant === 'line' && 'sc-tabs--line', p.variant === 'tiles' && 'sc-tabs--tiles', p.className), role: 'tablist', 'aria-label': p.label, onKeyDown: onKey },
      items.map(function (it) {
        var on = it.id === s[0];
        return h('button', { key: it.id, 'data-id': it.id, type: 'button', role: 'tab', tabIndex: on ? 0 : -1, className: 'sc-tab', 'aria-selected': on, onClick: function () { s[1](it.id); } },
          it.icon && h(Icon, { name: it.icon, size: 14 }), it.label, it.count != null && h('span', { className: 'sc-count' }, typeof it.count === 'number' ? it.count.toLocaleString('en-US') : it.count));
      }));
  }

  function Card(p) {
    var rest = omit(p, ['title', 'description', 'action', 'footer', 'children', 'className', 'flush']);
    return h('section', Object.assign({ className: cx('sc-card', p.className) }, rest),
      (p.title || p.action) && h('div', { className: 'sc-card__header' },
        h('div', null, p.title && h('h3', { className: 'sc-card__title' }, p.title), p.description && h('p', { className: 'sc-card__desc' }, p.description)),
        p.action),
      p.flush ? h('div', { className: 'sc-card__list' }, p.children) : p.children != null && h('div', { className: 'sc-card__content' }, p.children),
      p.footer && h('div', { className: 'sc-card__footer' }, p.footer));
  }

  function Spark(p) {
    var d = p.data || [], w = 200, hgt = 40;
    if (d.length < 2) return null;
    var min = Math.min.apply(null, d), max = Math.max.apply(null, d), span = max - min || 1;
    var pts = d.map(function (v, i) { return [(i * w) / (d.length - 1), 4 + (hgt - 8) * (1 - (v - min) / span)]; });
    var line = pts.map(function (q, i) { return (i ? 'L' : 'M') + q[0].toFixed(1) + ' ' + q[1].toFixed(1); }).join('');
    var last = pts[pts.length - 1];
    return h('svg', { className: 'sc-spark', viewBox: '0 0 ' + w + ' ' + hgt, preserveAspectRatio: 'none', 'aria-hidden': true },
      h('path', { className: 'sc-spark__area', d: line + 'L' + w + ' ' + hgt + 'L0 ' + hgt + 'Z' }),
      h('path', { className: 'sc-spark__line', d: line }),
      null);
  }
  function Stat(p) {
    var tone = p.deltaTone || 'neutral';
    return h('div', { className: cx('sc-card sc-stat', p.className) },
      h('div', { className: 'sc-stat__label' }, p.label),
      h('div', { className: 'sc-stat__row' },
        h('span', { className: 'sc-stat__value' }, p.value, p.unit && h('span', { className: 'sc-stat__unit' }, p.unit)),
        p.delta && h('span', { className: 'sc-stat__delta sc-stat__delta--' + tone }, h(Icon, { name: /^[-\u2212]/.test(p.delta) ? 'arrow-down-right' : 'arrow-up-right', size: 14 }), p.delta)),
      p.caption && h('div', { className: 'sc-stat__caption' }, p.caption),
      p.spark && h(Spark, { data: p.spark }));
  }

  var ALERT_ICON = { info: 'info', success: 'check-circle', warning: 'alert', danger: 'x-circle' };
  function Alert(p) {
    var tone = p.tone || 'info';
    return h('div', { className: cx('sc-alert', 'sc-alert--' + tone, p.className), role: tone === 'danger' ? 'alert' : 'status' },
      h(Icon, { name: ALERT_ICON[tone] }),
      p.title && h('div', { className: 'sc-alert__title' }, p.title),
      p.children && h('div', { className: 'sc-alert__desc' }, p.children),
      p.action && h('div', { className: 'sc-alert__action' }, p.action));
  }

  var OPS = { eq: '=', neq: '≠', gt: '>', gte: '≥', lt: '<', lte: '≤', in: 'in', nin: 'not in', like: 'contains', is_null: 'is empty' };
  function FilterChip(p) {
    var wire = 'filter[' + p.field + '][' + (p.op || 'eq') + ']=' + p.value;
    return h('span', { className: cx('sc-chip', p.className), title: wire },
      h('span', { className: 'sc-chip__body' },
        h('span', { className: 'sc-chip__field' }, p.label || p.field),
        h('span', { className: 'sc-chip__op' }, OPS[p.op || 'eq'] || p.op),
        h('span', { className: 'sc-chip__value' }, p.display || p.value)),
      h('button', { type: 'button', className: 'sc-chip__x', 'aria-label': 'Remove filter ' + (p.label || p.field), onClick: p.onRemove }, h(Icon, { name: 'x', size: 12 })));
  }

  function FacetedFilter(p) {
    var ref = useRef(null);
    var open = useState(!!p.defaultOpen);
    var q = useState('');
    var sel = useControllable(p.selected, p.defaultSelected || [], p.onChange);
    useDismiss(open[0], ref, function () { open[1](false); });
    var opts = (p.options || []).filter(function (o) { return !q[0] || String(o.label).toLowerCase().indexOf(q[0].toLowerCase()) >= 0; });
    function toggle(v) { sel[1](sel[0].indexOf(v) >= 0 ? sel[0].filter(function (x) { return x !== v; }) : sel[0].concat([v])); }
    var chosen = (p.options || []).filter(function (o) { return sel[0].indexOf(o.value) >= 0; });
    return h('div', { className: 'sc-facet', ref: ref },
      h(Button, { variant: 'outline', size: 'sm', icon: 'plus-circle', className: 'sc-facet__btn', 'data-active': chosen.length > 0, 'aria-expanded': open[0], 'aria-haspopup': 'listbox', onClick: function () { open[1](!open[0]); } },
        p.label,
        chosen.length > 0 && h('span', { className: 'sc-sep', style: { height: 14 } }),
        chosen.length > 0 && h('span', { className: 'sc-facet__vals' },
          chosen.length > 2 ? h('span', { className: 'sc-facet__val' }, chosen.length + ' selected')
            : chosen.map(function (o) { return h('span', { key: o.value, className: 'sc-facet__val' }, o.label); }))),
      open[0] && h('div', { className: 'sc-popover', role: 'listbox', 'aria-multiselectable': true, 'aria-label': p.label },
        h('label', { className: 'sc-popover__search' }, h(Icon, { name: 'search', size: 14 }), h('input', { value: q[0], placeholder: p.label, 'aria-label': 'Search ' + p.label, onChange: function (e) { q[1](e.target.value); }, 'data-autofocus': true })),
        opts.length === 0 && h('div', { className: 'sc-menu__label' }, 'No match.'),
        opts.map(function (o) {
          var on = sel[0].indexOf(o.value) >= 0;
          return h('button', { key: o.value, type: 'button', role: 'option', 'aria-selected': on, className: 'sc-menu__item', onClick: function () { toggle(o.value); } },
            h('input', { type: 'checkbox', className: 'sc-check', checked: on, readOnly: true, tabIndex: -1, 'aria-hidden': true }),
            o.icon, h('span', null, o.label), o.count != null && h('span', { className: 'sc-menu__meta' }, o.count));
        }),
        sel[0].length > 0 && h('div', { className: 'sc-menu__sep' }),
        sel[0].length > 0 && h('button', { type: 'button', className: 'sc-menu__item', style: { justifyContent: 'center' }, onClick: function () { sel[1]([]); } }, 'Clear filter')));
  }

  function DataTable(p) {
    var cols = p.columns || [], rows = p.rows || [];
    var phone = useMedia(PHONE);
    var keyOf = typeof p.rowKey === 'function' ? p.rowKey : function (r) { return r[p.rowKey || 'id']; };
    var sel = useControllable(p.selected, p.defaultSelected || [], p.onSelect);
    var act = useControllable(p.activeKey, p.defaultActiveKey, p.onActiveChange);
    var selected = sel[0];
    var allOn = rows.length > 0 && rows.every(function (r) { return selected.indexOf(keyOf(r)) >= 0; });
    var someOn = !allOn && rows.some(function (r) { return selected.indexOf(keyOf(r)) >= 0; });
    function toggleRow(k) { sel[1](selected.indexOf(k) >= 0 ? selected.filter(function (x) { return x !== k; }) : selected.concat([k])); }
    /* j/k or arrows move the active row, Enter opens it, x selects it. */
    function onKey(e) {
      if (e.target.tagName === 'INPUT' && e.key !== 'Enter') return;
      var keys = rows.map(keyOf), i = keys.indexOf(act[0]);
      if (e.key === 'ArrowDown' || e.key === 'j') { e.preventDefault(); act[1](keys[Math.min(keys.length - 1, i + 1)]); }
      else if (e.key === 'ArrowUp' || e.key === 'k') { e.preventDefault(); act[1](keys[Math.max(0, i - 1)]); }
      else if (e.key === 'Enter' && act[0] != null && p.onRowOpen) { e.preventDefault(); p.onRowOpen(rows[i]); }
      else if (e.key === 'x' && act[0] != null && p.selectable) { e.preventDefault(); toggleRow(act[0]); }
    }
    var head = h('tr', null,
      p.selectable && h('th', { className: 'sc-col-check' }, h(Checkbox, { checked: allOn, indeterminate: someOn, 'aria-label': 'Select all rows', onChange: function () { sel[1](allOn ? [] : rows.map(keyOf)); } })),
      cols.map(function (c) {
        var sorted = c.sort === 'asc' || c.sort === 'desc';
        var label = c.sortable
          ? h('button', { type: 'button', className: 'sc-th-sort', 'data-sorted': sorted, onClick: function () { if (p.onSort) p.onSort(c.key, c.sort === 'desc' ? 'asc' : 'desc'); } },
            c.header, h(Icon, { name: c.sort === 'asc' ? 'arrow-up' : c.sort === 'desc' ? 'arrow-down' : 'arrow-up-down', size: 12 }))
          : c.header;
        return h('th', { key: c.key, 'data-mobile': c.mobile, className: cx(c.align === 'end' && 'sc-col-end'), style: c.width ? { width: c.width } : undefined, 'aria-sort': c.sort === 'asc' ? 'ascending' : c.sort === 'desc' ? 'descending' : undefined }, label);
      }),
      p.rowActions && h('th', { className: 'sc-col-end', style: { width: 1 } }, h('span', { className: 'sc-sr' }, 'Actions')));
    var span = cols.length + (p.selectable ? 1 : 0) + (p.rowActions ? 1 : 0);
    var body;
    if (p.loading) {
      body = [0, 1, 2, 3, 4].map(function (i) { return h('tr', { key: 'sk' + i }, Array.from({ length: span }).map(function (_, j) { return h('td', { key: j }, h(Skeleton, { width: j === 0 && p.selectable ? 16 : (60 + ((i * 7 + j * 13) % 40)) + '%' })); })); });
    } else if (rows.length === 0) {
      body = h('tr', null, h('td', { colSpan: span, style: { height: 'auto', padding: 0 } }, p.empty || h(EmptyState, { icon: 'search', title: 'No results', description: 'Nothing matches these filters. Remove a filter or widen the date range.' })));
    } else {
      body = rows.map(function (r) {
        var k = keyOf(r), on = selected.indexOf(k) >= 0;
        return h('tr', {
          key: k, 'aria-selected': p.selectable ? on : undefined, 'data-active': act[0] === k ? true : undefined,
          onClick: p.onRowOpen ? function (e) { if (e.target.closest('button, a, input, label')) return; act[1](k); p.onRowOpen(r); } : undefined,
          style: p.onRowOpen ? { cursor: 'pointer' } : undefined
        },
          p.selectable && h('td', { className: 'sc-col-check' }, h(Checkbox, { checked: on, 'aria-label': 'Select ' + k, onChange: function () { toggleRow(k); } })),
          cols.map(function (c) { return h('td', { key: c.key, 'data-mobile': c.mobile, className: cx(c.align === 'end' && 'sc-col-end', c.mono && 'sc-mono') }, c.render ? c.render(r) : r[c.key]); }),
          p.rowActions && h('td', { className: 'sc-col-end' }, h('span', { className: 'sc-row-actions' }, p.rowActions(r))));
      });
    }
    var cards = p.layout === 'cards' || (p.layout !== 'table' && phone);
    if (cards && !p.loading && rows.length) {
      var role = function (r) { return cols.filter(function (c) { return c.mobile === r; }); };
      var prim = role('primary')[0] || cols[0], badge = role('badge')[0], sec = role('secondary'), meta = role('meta')[0];
      var txt = function (c, r) { return c.cardText ? c.cardText(r) : c.render ? c.render(r) : r[c.key]; };
      var selecting = p.selectable && (p.selecting || selected.length > 0);
      return h('div', { className: cx('sc-table-card', p.className) },
        h('ul', { className: 'sc-cards', 'aria-label': p.caption },
          rows.map(function (r) {
            var k = keyOf(r), on = selected.indexOf(k) >= 0;
            return h('li', { key: k, className: cx('sc-cardrow', selecting && 'sc-cardrow--selecting'), 'aria-selected': p.selectable ? on : undefined, tabIndex: 0,
              onClick: function (e) { if (e.target.closest('button, a, input, label')) return; if (selecting) { toggleRow(k); return; } act[1](k); if (p.onRowOpen) p.onRowOpen(r); },
              onKeyDown: function (e) { if (e.key === 'Enter' && p.onRowOpen) p.onRowOpen(r); } },
              selecting && h('span', { className: 'sc-cardrow__check' }, h(Checkbox, { checked: on, 'aria-label': 'Select ' + k, onChange: function () { toggleRow(k); } })),
              h('span', { className: 'sc-cardrow__primary' }, txt(prim, r)),
              h('span', { className: 'sc-cardrow__badge' }, badge ? txt(badge, r) : null),
              h('span', { className: 'sc-cardrow__secondary' }, sec.map(function (c) { return txt(c, r); }).filter(Boolean).reduce(function (acc, x, i) { return i ? acc.concat([' \u00b7 ', x]) : [x]; }, [])),
              h('span', { className: 'sc-cardrow__meta' }, meta ? txt(meta, r) : null));
          })),
        p.footer && h('div', { className: 'sc-table__foot' }, p.footer));
    }
    return h('div', { className: cx('sc-table-card', p.className) },
      h('div', { className: 'sc-table-scroll' },
        h('table', { className: 'sc-table', 'data-density': p.density || 'comfortable', 'aria-busy': p.loading ? true : undefined },
          p.caption && h('caption', { className: 'sc-sr' }, p.caption),
          h('thead', null, head),
          h('tbody', { tabIndex: rows.length ? 0 : undefined, onKeyDown: onKey, 'aria-label': p.caption ? p.caption + ', use j and k to move, Enter to open' : undefined }, body))),
      p.footer && h('div', { className: 'sc-table__foot' }, p.footer));
  }

  function BulkBar(p) {
    if (!p.count) return null;
    return h('div', { className: cx('sc-bulkbar', p.className), role: 'toolbar', 'aria-label': p.count + ' selected' },
      h('span', { className: 'sc-bulkbar__count' }, p.count + ' selected'),
      p.children,
      h('span', { className: 'sc-sep' }),
      h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'x', onClick: p.onClear }, 'Clear selection'));
  }

  function DropdownMenu(p) {
    var ref = useRef(null);
    var open = useState(!!p.defaultOpen);
    var act = useState(-1);
    useDismiss(open[0], ref, function () { open[1](false); });
    var items = p.items || [];
    var actionable = items.map(function (it, i) { return it.separator || it.heading || it.disabled ? -1 : i; }).filter(function (i) { return i >= 0; });
    function onKey(e) {
      if (!open[0]) return;
      var pos = actionable.indexOf(act[0]);
      if (e.key === 'ArrowDown') { e.preventDefault(); act[1](actionable[(pos + 1) % actionable.length]); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); act[1](actionable[(pos - 1 + actionable.length) % actionable.length]); }
      else if (e.key === 'Enter' && act[0] >= 0) { e.preventDefault(); var it = items[act[0]]; open[1](false); if (it.onSelect) it.onSelect(); }
    }
    var trigger = p.trigger || h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'more' }, p.label || 'More actions');
    return h('div', { className: 'sc-facet', ref: ref, onKeyDown: onKey },
      React.cloneElement(trigger, { 'aria-haspopup': 'menu', 'aria-expanded': open[0], onClick: function (e) { e.stopPropagation(); open[1](!open[0]); act[1](-1); } }),
      open[0] && h('div', { className: cx('sc-popover', p.align !== 'start' && 'sc-popover--end'), role: 'menu' },
        items.map(function (it, i) {
          if (it.separator) return h('div', { key: i, className: 'sc-menu__sep', role: 'separator' });
          if (it.heading) return h('div', { key: i, className: 'sc-menu__label' }, it.heading);
          return h('button', {
            key: i, type: 'button', role: 'menuitem', disabled: it.disabled, 'data-active': act[0] === i, className: cx('sc-menu__item', it.danger && 'sc-menu__item--danger'),
            onMouseEnter: function () { act[1](i); }, onClick: function (e) { e.stopPropagation(); open[1](false); if (it.onSelect) it.onSelect(); }
          }, it.icon && h(Icon, { name: it.icon }), it.label, it.shortcut && h('span', { className: 'sc-menu__meta' }, h(Kbd, { keys: it.shortcut })));
        })));
  }

  function Tooltip(p) {
    var st = useState(!!p.defaultOpen), timer = useRef(null);
    function show() { clearTimeout(timer.current); timer.current = setTimeout(function () { st[1](true); }, 400); }
    function hide() { clearTimeout(timer.current); st[1](false); }
    return h('span', { className: 'sc-tooltip-wrap', onMouseEnter: show, onMouseLeave: hide, onFocus: function () { st[1](true); }, onBlur: hide },
      p.children, st[0] && h('span', { className: 'sc-tooltip', role: 'tooltip' }, p.content, p.shortcut && h(Kbd, { keys: p.shortcut })));
  }

  function Dialog(p) {
    var ref = useRef(null);
    var phone = useMedia(PHONE);
    var dragHead = useDragDismiss(ref, p.onClose, phone);
    useFocusReturn(p.open, ref);
    useEffect(function () {
      if (!p.open) return undefined;
      function onKey(e) { if (e.key === 'Escape' && p.onClose) p.onClose(); }
      document.addEventListener('keydown', onKey);
      return function () { document.removeEventListener('keydown', onKey); };
    }, [p.open]);
    if (!p.open) return null;
    var titleId = 'dlg-' + String(p.title).replace(/\W+/g, '-').toLowerCase();
    return h(React.Fragment, null,
      h('div', { className: cx('sc-overlay', p.contained && 'sc-overlay--contained'), onClick: p.onClose }),
      h('div', { ref: ref, className: 'sc-dialog', role: p.role || 'dialog', 'aria-modal': true, 'aria-labelledby': titleId },
        h('span', Object.assign({ className: 'sc-dialog__grab', 'aria-hidden': true }, dragHead)),
        h('div', Object.assign({ className: 'sc-dialog__head' }, dragHead), h('h2', { id: titleId, className: 'sc-dialog__title' }, p.title), p.description && h('p', { className: 'sc-dialog__desc' }, p.description)),
        p.children,
        p.footer && h('div', { className: 'sc-dialog__foot' }, p.footer),
        h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'x', className: 'sc-dialog__close', onClick: p.onClose }, 'Close')));
  }

  function Sheet(p) {
    var ref = useRef(null);
    var phone = useMedia(PHONE);
    var bottom = p.placement === 'bottom' || (p.placement !== 'side' && phone);
    var dragHead = useDragDismiss(ref, p.onClose, bottom);
    useEffect(function () {
      if (!p.open) return undefined;
      function onKey(e) { if (e.key === 'Escape' && p.onClose) p.onClose(); }
      document.addEventListener('keydown', onKey);
      return function () { document.removeEventListener('keydown', onKey); };
    }, [p.open]);
    if (!p.open) return null;
    var modal = bottom || p.modal !== false;
    var actions = p.actions ? [].concat(p.actions) : null;
    return h(React.Fragment, null,
      modal && h('div', { className: cx('sc-overlay', p.contained && 'sc-overlay--contained'), onClick: p.onClose }),
      h('aside', { ref: ref, className: cx('sc-sheet', bottom && 'sc-sheet--bottom'), role: 'dialog', 'aria-modal': modal, 'aria-label': typeof p.title === 'string' ? p.title : 'Details', style: p.contained ? { position: 'absolute' } : undefined },
        h('span', Object.assign({ className: 'sc-sheet__grab', 'aria-hidden': true }, dragHead)),
        h('div', Object.assign({ className: 'sc-sheet__head' }, dragHead),
          p.eyebrow && h('div', { className: 'sc-sheet__eyebrow' }, p.eyebrow),
          h('h2', { className: cx('sc-sheet__title', p.figure && 'sc-sheet__title--figure') }, p.title),
          p.description && h('div', { className: 'sc-dialog__desc', style: { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' } }, p.description),
          actions && !bottom && h('div', { className: 'sc-sheet__actions' }, actions)),
        h('div', { className: 'sc-sheet__nav' },
          p.onPrev && h('span', { 'data-step': true }, h(Tooltip, { content: 'Previous', shortcut: ['k'] }, h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'chevron-up', onClick: p.onPrev }, 'Previous record'))),
          p.onNext && h('span', { 'data-step': true }, h(Tooltip, { content: 'Next', shortcut: ['j'] }, h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'chevron-down', onClick: p.onNext }, 'Next record'))),
          h(Tooltip, { content: 'Close', shortcut: ['esc'] }, h(Button, { variant: 'ghost', size: 'sm', iconOnly: true, icon: 'x', onClick: p.onClose }, 'Close'))),
        h('div', { className: 'sc-sheet__body' }, p.children),
        actions && bottom && h('div', { className: 'sc-sheet__dock' }, actions),
        p.footer && h('div', { className: 'sc-sheet__foot' }, p.footer)));
  }

  var TOAST_ICON = { success: 'check-circle', danger: 'x-circle', info: 'info', neutral: 'info' };
  /* const [toasts, toast, dismiss] = useToasts(); toast({title, description, tone, action: {label, onClick}}) */
  function useToasts(duration) {
    var st = useState([]), seq = useRef(0);
    function dismiss(id) { st[1](function (list) { return list.filter(function (t) { return t.id !== id; }); }); }
    function push(t) {
      var id = ++seq.current;
      st[1](function (list) { return list.concat([Object.assign({ id: id }, t)]).slice(-3); });
      setTimeout(function () { dismiss(id); }, t.duration || duration || 6000);
      return id;
    }
    return [st[0], push, dismiss];
  }
  function Toaster(p) {
    return h('div', { className: cx('sc-toaster', p.contained && 'sc-toaster--contained'), role: 'region', 'aria-label': 'Notifications', 'aria-live': 'polite' },
      (p.toasts || []).map(function (t) {
        var tone = t.tone || 'success';
        return h('div', { key: t.id, className: 'sc-toast sc-toast--' + tone, role: tone === 'danger' ? 'alert' : 'status' },
          h(Icon, { name: TOAST_ICON[tone] }),
          h('div', { className: 'sc-toast__title' }, t.title),
          t.description && h('div', { className: 'sc-toast__desc' }, t.description),
          h('div', { className: 'sc-toast__action' },
            t.action && h(Button, { size: 'sm', variant: 'outline', icon: t.action.icon, onClick: function () { t.action.onClick(); if (p.onDismiss) p.onDismiss(t.id); } }, t.action.label),
            h(Button, { size: 'sm', variant: 'ghost', iconOnly: true, icon: 'x', onClick: function () { if (p.onDismiss) p.onDismiss(t.id); } }, 'Dismiss')));
      }));
  }

  function highlight(text, q) {
    if (!q) return text;
    var i = String(text).toLowerCase().indexOf(q.toLowerCase());
    if (i < 0) return text;
    return [String(text).slice(0, i), h('mark', { key: 'm', className: 'sc-mark' }, String(text).slice(i, i + q.length)), String(text).slice(i + q.length)];
  }
  function CommandPalette(p) {
    var ref = useRef(null);
    var q = useState(p.defaultQuery || '');
    var act = useState(0);
    useFocusReturn(p.open, ref);
    useEffect(function () { act[1](0); }, [q[0]]);
    if (!p.open) return null;
    var needle = q[0].trim().toLowerCase();
    var groups = (p.groups || []).map(function (g) {
      return { label: g.label, items: g.items.filter(function (it) { return !needle || (it.label + ' ' + (it.keywords || '') + ' ' + (it.hint || '')).toLowerCase().indexOf(needle) >= 0; }) };
    }).filter(function (g) { return g.items.length; });
    var flat = []; groups.forEach(function (g) { g.items.forEach(function (it) { flat.push(it); }); });
    function run(it) { if (it.onSelect) it.onSelect(); if (p.onClose) p.onClose(); }
    function onKey(e) {
      if (e.key === 'ArrowDown') { e.preventDefault(); act[1](Math.min(flat.length - 1, act[0] + 1)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); act[1](Math.max(0, act[0] - 1)); }
      else if (e.key === 'Enter' && flat[act[0]]) { e.preventDefault(); run(flat[act[0]]); }
      else if (e.key === 'Escape' && p.onClose) { p.onClose(); }
    }
    var n = -1;
    return h(React.Fragment, null,
      h('div', { className: cx('sc-overlay', p.contained && 'sc-overlay--contained'), onClick: p.onClose }),
      h('div', { ref: ref, className: 'sc-command', role: 'dialog', 'aria-modal': true, 'aria-label': 'Command palette', onKeyDown: onKey },
        h('div', { className: 'sc-command__input' }, h(Icon, { name: 'search' }),
          h('input', { 'data-autofocus': true, value: q[0], placeholder: p.placeholder || 'Search or jump to…', 'aria-label': 'Search commands', role: 'combobox', 'aria-expanded': true, 'aria-controls': 'sc-cmd-list', onChange: function (e) { q[1](e.target.value); } }),
          p.scope && h('span', { className: 'sc-command__scope' }, p.scope)),
        h('div', { className: 'sc-command__list', id: 'sc-cmd-list', role: 'listbox' },
          flat.length === 0 && h('div', { className: 'sc-command__empty' }, 'No results for “' + q[0] + '”. Try an invoice number, a tenant code or an app.'),
          groups.map(function (g) {
            return h('div', { key: g.label, className: 'sc-command__group', role: 'group', 'aria-label': g.label },
              h('div', { className: 'sc-menu__label' }, g.label),
              g.items.map(function (it) {
                n += 1; var idx = n;
                return h('button', { key: it.id || it.label, type: 'button', role: 'option', 'aria-selected': act[0] === idx, 'data-active': act[0] === idx, className: 'sc-menu__item sc-command__item', onMouseMove: function () { act[1](idx); }, onClick: function () { run(it); } },
                  it.app ? h(AppMark, { app: it.app, size: 'sm' }) : it.icon && h(Icon, { name: it.icon }),
                  it.hint ? h('span', { className: 'sc-cell-2' }, h('span', null, highlight(it.label, q[0].trim())), h('span', null, it.hint)) : h('span', null, highlight(it.label, q[0].trim())),
                  it.shortcut ? h('span', { className: 'sc-menu__meta' }, h(Kbd, { keys: it.shortcut })) : it.meta && h('span', { className: 'sc-menu__meta' }, it.meta));
              }));
          })),
        h('div', { className: 'sc-command__foot' },
          h('span', null, h(Kbd, { keys: ['up', 'down'] }), 'Move'), h('span', null, h(Kbd, { keys: ['enter'] }), 'Open'), h('span', null, h(Kbd, { keys: ['esc'] }), 'Close'))));
  }

  function Skeleton(p) {
    return h('span', { className: cx('sc-skel', p.className), style: { width: p.width || '100%', height: p.height || 12, borderRadius: p.circle ? '999px' : undefined }, 'aria-hidden': true });
  }

  function EmptyState(p) {
    return h('div', { className: cx('sc-empty', p.className) },
      p.icon && h('div', { className: 'sc-empty__icon' }, h(Icon, { name: p.icon, size: 20 })),
      h('h3', { className: 'sc-empty__title' }, p.title),
      p.description && h('p', { className: 'sc-empty__desc' }, p.description),
      p.children && h('div', { className: 'sc-empty__actions' }, p.children));
  }

  window.Suite = Object.assign(window.Suite || {}, {
    Button: Button, Field: Field, Checkbox: Checkbox, Switch: Switch, StatusBadge: StatusBadge, AppMark: AppMark, Money: Money, CopyId: CopyId, Kbd: Kbd,
    Tabs: Tabs, Card: Card, Stat: Stat, Alert: Alert, FilterChip: FilterChip, FacetedFilter: FacetedFilter, DataTable: DataTable, BulkBar: BulkBar,
    DropdownMenu: DropdownMenu, Tooltip: Tooltip, Dialog: Dialog, Sheet: Sheet, Toaster: Toaster, CommandPalette: CommandPalette, Skeleton: Skeleton,
    EmptyState: EmptyState, Icon: Icon, useToasts: useToasts, useHotkey: useHotkey, useMedia: useMedia
  });
})();
