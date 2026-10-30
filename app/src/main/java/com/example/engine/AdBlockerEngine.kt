package com.example.engine

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlockerEngine {

    /**
     * Easily editable domain blocklist for ads, trackers, and popups across all sites & YouTube.
     */
    val BLOCKED_AD_DOMAINS: Set<String> = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googleads.g.doubleclick.net",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "securepubads.g.doubleclick.net",
        "tpc.googlesyndication.com",
        "2mdn.net",
        "moatads.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "amazon-adsystem.com",
        "scorecardresearch.com",
        "quantserve.com",
        "zedo.com",
        "yandexadexchange.net",
        "ads-twitter.com",
        "an.facebook.com"
    )

    val BLOCKED_URL_FRAGMENTS: List<String> = listOf(
        "/api/stats/ads",
        "/pagead/lvz",
        "/pagead/interaction",
        "/pagead/adview",
        "/ptracking?",
        "ad_type=",
        "adformat=",
        "/get_midroll_info"
    )

    fun shouldBlockRequest(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        val host = runCatching { Uri.parse(lower).host.orEmpty() }.getOrDefault("")
        if (host.isNotEmpty()) {
            val matchedDomain = BLOCKED_AD_DOMAINS.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
            if (matchedDomain) return true
        }
        return BLOCKED_URL_FRAGMENTS.any { fragment -> lower.contains(fragment) }
    }

    fun createEmptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Injects CSS to hide ad containers/overlays, blocks window.open popups without explicit
     * recent user tap, auto-clicks skip buttons, and fast-forwards unskippable video ads.
     */
    fun buildAdBlockAndPopupScript(enabled: Boolean): String {
        if (!enabled) {
            return """
            (function() {
                var css = document.getElementById('__rxl_adblock_css');
                if (css) css.remove();
                window.__rxlAdBlockEnabled = false;
            })();
            """.trimIndent()
        }

        return """
        (function() {
            window.__rxlAdBlockEnabled = true;

            // 1. Block popups & unrequested window.open redirects
            if (!window.__rxlPopupGuardInstalled) {
                window.__rxlPopupGuardInstalled = true;
                window.__rxlLastUserTap = 0;
                document.addEventListener('pointerdown', function() {
                    window.__rxlLastUserTap = Date.now();
                }, true);
                var origWindowOpen = window.open;
                window.open = function(url) {
                    if (window.__rxlAdBlockEnabled) {
                        // Block all popup windows in the video player
                        return null;
                    }
                    return origWindowOpen ? origWindowOpen.apply(this, arguments) : null;
                };
            }

            // 2. Inject cosmetic ad-hiding CSS
            var styleId = '__rxl_adblock_css';
            if (!document.getElementById(styleId)) {
                var st = document.createElement('style');
                st.id = styleId;
                st.textContent = [
                    '.ytp-ad-overlay-container, .ytp-ad-message-container, .ytp-ad-image-overlay,',
                    '.ytp-ad-text-overlay, .ytp-ad-ActionInterstitialContainer,',
                    'ytd-promoted-sparkles-web-renderer, ytm-promoted-sparkles-web-renderer,',
                    'ytd-display-ad-renderer, ytm-companion-ad-renderer, ytd-companion-slot-renderer,',
                    '#player-ads, #masthead-ad, .video-ads, .adsbygoogle,',
                    '[id^="google_ads_"], [id^="div-gpt-ad"], [class*="ad-container"],',
                    '[class*="popup-ad"], [id*="popup-ad"] {',
                    '  display: none !important; visibility: hidden !important;',
                    '  opacity: 0 !important; pointer-events: none !important;',
                    '  width: 0 !important; height: 0 !important;',
                    '}'
                ].join('\n');
                (document.head || document.documentElement).appendChild(st);
            }

            // 3. Auto-skip & speed-through video ads (including YouTube unskippable ads)
            if (!window.__rxlAdSkipperTimer) {
                window.__rxlAdSkipperTimer = setInterval(function() {
                    if (!window.__rxlAdBlockEnabled) return;
                    try {
                        var skipSelectors = [
                            '.ytp-ad-skip-button',
                            '.ytp-ad-skip-button-modern',
                            '.ytp-skip-ad-button',
                            '.ytp-ad-overlay-close-button',
                            '.videoAdUiSkipButton',
                            '[class*="skip-ad" i]',
                            'button[id*="skip-ad" i]'
                        ];
                        for (var i = 0; i < skipSelectors.length; i++) {
                            var btns = document.querySelectorAll(skipSelectors[i]);
                            for (var b = 0; b < btns.length; b++) {
                                if (btns[b] && typeof btns[b].click === 'function') {
                                    btns[b].click();
                                }
                            }
                        }

                        var playerEl = document.querySelector('#movie_player, .html5-video-player');
                        var isAdPlaying = !!(
                            (playerEl && playerEl.classList.contains('ad-showing')) ||
                            document.querySelector('.ad-showing, .ytp-ad-player-overlay, .ytp-ad-preview-container')
                        );
                        var vid = document.querySelector('video');
                        if (vid && isAdPlaying) {
                            vid.muted = true;
                            vid.playbackRate = 16.0;
                            if (isFinite(vid.duration) && vid.duration > 0.5 && vid.duration < 180) {
                                vid.currentTime = Math.max(vid.currentTime, vid.duration - 0.1);
                            }
                            window.__rxlWasAcceleratingAd = true;
                        } else if (vid && window.__rxlWasAcceleratingAd) {
                            window.__rxlWasAcceleratingAd = false;
                            vid.playbackRate = 1.0;
                            if (typeof window.__rxlDesiredMute === 'boolean') {
                                vid.muted = window.__rxlDesiredMute;
                            }
                        }
                    } catch (e) {}
                }, 400);
            }
        })();
        """.trimIndent()
    }
}
