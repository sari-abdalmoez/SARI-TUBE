package com.sari.tube.ui.player

/**
 * Focuses an embedded page on its video player: finds the largest real player, hides the rest of the
 * page, and fills the screen with the player.
 *
 * The script is installed once per page as window.__sariFocus. Its methods are called from Kotlin:
 * - apply(): focuses on the player; returns "true" when a player was found and focused.
 * - reset(): restores every style the script changed, so the whole page is shown again.
 *
 * Automatic focusing runs when the page loads. It keeps up with pages that build their player later,
 * with a throttled MutationObserver. Ad-like frames are ignored, and no page content is removed.
 */
internal object VideoFocus {
    /** Installs the script (or re-applies it on the same page) and focuses the player if one is found. */
    const val SCRIPT = """
(function () {
  if (window.__sariFocus) { return window.__sariFocus.apply(); }

  var undo = [];
  var applied = false;
  var currentTarget = null;
  var autoMode = true;
  var observer = null;
  var scheduled = false;

  function hintOf(el) {
    var cls = typeof el.className === 'string' ? el.className : '';
    return ((el.id || '') + ' ' + cls + ' ' + (el.getAttribute('src') || '')).toLowerCase();
  }

  function isAdLike(el) {
    return /doubleclick|googlesyndication|banner|advert|[\/_.-]ads?[\/_.-]/.test(hintOf(el));
  }

  function findTarget() {
    var nodes = document.querySelectorAll('video, iframe');
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (isAdLike(el)) continue;
      var r = el.getBoundingClientRect();
      var s = window.getComputedStyle(el);
      if (s.display === 'none' || s.visibility === 'hidden') continue;
      if (r.width < 200 || r.height < 110) continue;
      var isVideo = el.tagName === 'VIDEO';
      var playerHint = /player|video|embed|stream|watch/.test(hintOf(el));
      var ratio = r.width / Math.max(r.height, 1);
      var wideEnough = ratio > 1.2 && ratio < 2.6 && r.width >= 400;
      if (!isVideo && !playerHint && !wideEnough) continue;
      var score = r.width * r.height * (isVideo ? 1.5 : 1) * (playerHint ? 1.2 : 1);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  // Climbs to the wrapper that holds only the player, not the sidebar or the title beside it.
  function frameFor(target) {
    var tr = target.getBoundingClientRect();
    var frame = target;
    for (var i = 0; i < 3; i++) {
      var parent = frame.parentElement;
      if (!parent || parent === document.body || parent === document.documentElement) break;
      var pr = parent.getBoundingClientRect();
      if (pr.width > tr.width * 1.05 || pr.height > tr.height * 1.6) break;
      frame = parent;
    }
    return frame;
  }

  function remember(el, prop) {
    undo.push({
      el: el,
      prop: prop,
      value: el.style.getPropertyValue(prop),
      priority: el.style.getPropertyPriority(prop)
    });
  }

  function force(el, prop, value) {
    remember(el, prop);
    el.style.setProperty(prop, value, 'important');
  }

  function hideOthers(frame, target) {
    var node = frame;
    while (node && node !== document.body && node.parentElement) {
      var owner = node.parentElement;
      for (var i = 0; i < owner.children.length; i++) {
        var sib = owner.children[i];
        if (sib !== node && !sib.contains(target) && sib.tagName !== 'SCRIPT' && sib.tagName !== 'STYLE') {
          force(sib, 'display', 'none');
        }
      }
      node = owner;
    }
  }

  function restoreLock() {
    for (var i = undo.length - 1; i >= 0; i--) {
      var u = undo[i];
      if (u.value) { u.el.style.setProperty(u.prop, u.value, u.priority); }
      else { u.el.style.removeProperty(u.prop); }
    }
    undo = [];
    applied = false;
    currentTarget = null;
  }

  function applyLock() {
    var target = findTarget();
    if (!target) { return applied; }
    // Already focused on this player: nothing to change, which keeps repeated checks cheap.
    if (applied && target === currentTarget) { return true; }

    restoreLock();
    var frame = frameFor(target);
    hideOthers(frame, target);
    var props = [
      ['position', 'fixed'], ['top', '0'], ['left', '0'], ['right', '0'], ['bottom', '0'],
      ['width', '100vw'], ['height', '100vh'], ['max-width', '100vw'], ['max-height', '100vh'],
      ['margin', '0'], ['padding', '0'], ['overflow', 'hidden'], ['z-index', '2147483646'],
      ['background', '#000']
    ];
    for (var i = 0; i < props.length; i++) { force(frame, props[i][0], props[i][1]); }

    force(target, 'width', '100%');
    force(target, 'height', '100%');
    force(target, 'max-width', '100%');
    force(target, 'max-height', '100%');
    if (target.tagName === 'VIDEO') { force(target, 'object-fit', 'contain'); }

    force(document.documentElement, 'overflow', 'hidden');
    force(document.body, 'overflow', 'hidden');
    force(document.body, 'margin', '0');
    force(document.body, 'background', '#000');

    applied = true;
    currentTarget = target;
    return true;
  }

  function schedule() {
    if (scheduled || !autoMode) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      if (autoMode) { applyLock(); }
    }, 400);
  }

  function startObserving() {
    if (observer || !document.documentElement) return;
    observer = new MutationObserver(schedule);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  function stopObserving() {
    if (observer) { observer.disconnect(); observer = null; }
  }

  window.__sariFocus = {
    apply: function () {
      autoMode = true;
      startObserving();
      return applyLock();
    },
    reset: function () {
      autoMode = false;
      stopObserving();
      restoreLock();
      return true;
    },
    isApplied: function () { return applied; }
  };

  var tries = 0;
  var timer = setInterval(function () {
    tries++;
    if (!autoMode || applied || tries > 20) { clearInterval(timer); return; }
    applyLock();
  }, 500);

  startObserving();
  return applyLock();
})();
"""

    /** Focuses the player now. Evaluates to "true" when a player was focused, "false" otherwise. */
    const val APPLY = "window.__sariFocus ? window.__sariFocus.apply() : false"

    /** Restores the whole page. */
    const val RESET = "window.__sariFocus ? window.__sariFocus.reset() : true"
}
