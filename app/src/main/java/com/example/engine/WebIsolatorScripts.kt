package com.example.engine

import com.example.model.DisplayScaleMode
import com.example.model.VideoQualityPreference

object WebIsolatorScripts {

    fun cssObjectFit(mode: DisplayScaleMode): String = when (mode) {
        DisplayScaleMode.FIT -> "contain"
        DisplayScaleMode.FILL -> "cover"
        DisplayScaleMode.STRETCH -> "fill"
    }

    /**
     * Injected early into every web page to sniff raw .m3u8 / .mp4 streams from JS fetch/XHR/video.src,
     * pre-seed YouTube 1080p/720p quality storage, enforce inline playback, and notify RxlBridge of ad states.
     */
    const val STREAM_SNIFFER_AND_AD_GUARD_JS = """
        (function() {
            if (window.__rxlHookInstalled) return;
            window.__rxlHookInstalled = true;

            // Pre-seed YouTube HTML5 localStorage quality preference to HD (1080p / min 720p)
            try {
                var now = Date.now();
                var exp = now + 2592000000;
                var qObj = JSON.stringify({
                    data: JSON.stringify({ quality: 1080, previousQuality: 720 }),
                    expiration: exp,
                    creation: now
                });
                localStorage.setItem('yt-player-quality', qObj);
            } catch (e) {}

            function reportUrl(u, source) {
                try {
                    if (!u || typeof u !== 'string') return;
                    if (u.startsWith('blob:') || u.startsWith('data:')) return;
                    var lower = u.toLowerCase();
                    if (lower.indexOf('.m3u8') !== -1 ||
                        lower.indexOf('.mp4') !== -1 ||
                        lower.indexOf('.mpd') !== -1 ||
                        lower.indexOf('.webm') !== -1 ||
                        lower.indexOf('mime=video') !== -1 ||
                        lower.indexOf('videoplayback') !== -1) {
                        if (window.RxlBridge && window.RxlBridge.onStreamSniffed) {
                            window.RxlBridge.onStreamSniffed(u, source || 'js');
                        }
                    }
                } catch (e) {}
            }

            try {
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    reportUrl(String(url), 'xhr');
                    return origOpen.apply(this, arguments);
                };
            } catch (e) {}

            try {
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input) {
                        var u = (typeof input === 'string') ? input : (input && input.url);
                        reportUrl(u, 'fetch');
                        return origFetch.apply(this, arguments);
                    };
                }
            } catch (e) {}

            setInterval(function() {
                try {
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        var v = vids[i];
                        v.setAttribute('playsinline', 'true');
                        v.setAttribute('webkit-playsinline', 'true');
                        if (v.currentSrc) reportUrl(v.currentSrc, 'video_tag');
                        if (v.src) reportUrl(v.src, 'video_src');
                    }

                    var adShowing = !!(
                        document.querySelector('.ad-showing, .ytp-ad-player-overlay, [data-a-target="video-ad-label"]')
                    );
                    if (window.__rxlLastAd !== adShowing) {
                        window.__rxlLastAd = adShowing;
                        if (window.RxlBridge && window.RxlBridge.onAdStateChanged) {
                            window.RxlBridge.onAdStateChanged(adShowing);
                        }
                    }
                } catch (e) {}
            }, 900);
        })();
    """

    /**
     * Ancestor-preserving Universal Cinema Video Isolator + Minimum 720p Quality Enforcer + Audio Ducking:
     * Keeps React/Polymer/TikTok/Instagram/YouTube/HLS DOM trees intact while visually hiding
     * every sibling, header, comment, and popup on the page, forcing >= 720p quality on YouTube
     * (#movie_player setPlaybackQualityRange) and HLS players, and lowering volume while AI speaks.
     */
    fun buildCinemaIsolatorJs(
        shouldPlay: Boolean,
        shouldMute: Boolean,
        isAudioDucked: Boolean,
        qualityPreference: VideoQualityPreference,
        scaleMode: DisplayScaleMode,
        isolateChrome: Boolean
    ): String {
        val fit = cssObjectFit(scaleMode)
        val playFlag = if (shouldPlay) "true" else "false"
        val muteFlag = if (shouldMute) "true" else "false"
        val duckFlag = if (isAudioDucked) "true" else "false"
        val isolateFlag = if (isolateChrome) "true" else "false"
        val qualityModeName = qualityPreference.name

        return """
        (function() {
            window.__rxlDesiredPlay = $playFlag;
            window.__rxlDesiredMute = $muteFlag;
            window.__rxlDesiredDuck = $duckFlag;
            window.__rxlQualityMode = '$qualityModeName';
            window.__rxlObjectFit = '$fit';
            window.__rxlIsolate = $isolateFlag;

            function enforceWebQuality() {
                try {
                    var qMode = window.__rxlQualityMode || 'AUTO_720P_MIN';
                    var targetNum = (qMode === 'FORCE_720P') ? 720 : 1080;
                    try {
                        var now = Date.now();
                        localStorage.setItem('yt-player-quality', JSON.stringify({
                            data: JSON.stringify({ quality: targetNum, previousQuality: 720 }),
                            expiration: now + 2592000000,
                            creation: now
                        }));
                    } catch (e) {}

                    // YouTube desktop/mobile #movie_player API hook (re-applied on quality change & new video)
                    var ytPlayer = document.getElementById('movie_player') ||
                        document.querySelector('.html5-video-player');
                    if (ytPlayer) {
                        var levels = (typeof ytPlayer.getAvailableQualityLevels === 'function')
                            ? (ytPlayer.getAvailableQualityLevels() || [])
                            : [];
                        var hdRanks = ['highres', 'hd2160', 'hd1440', 'hd1080', 'hd720'];
                        var has720Plus = false;
                        for (var i = 0; i < levels.length; i++) {
                            if (hdRanks.indexOf(levels[i]) !== -1) {
                                has720Plus = true;
                                break;
                            }
                        }
                        var minQ = 'hd720';
                        var maxQ = 'highres';
                        if (qMode === 'FORCE_1080P') {
                            minQ = (!levels.length || levels.indexOf('hd1080') !== -1) ? 'hd1080' : (has720Plus ? 'hd720' : (levels[0] || 'hd1080'));
                            maxQ = 'hd1080';
                        } else if (qMode === 'FORCE_720P') {
                            minQ = (!levels.length || levels.indexOf('hd720') !== -1) ? 'hd720' : (levels[0] || 'hd720');
                            maxQ = minQ;
                        } else {
                            minQ = (!levels.length || has720Plus) ? 'hd720' : (levels[0] || 'hd720');
                            maxQ = (!levels.length || has720Plus) ? 'highres' : minQ;
                        }

                        if (typeof ytPlayer.setPlaybackQualityRange === 'function') {
                            ytPlayer.setPlaybackQualityRange(minQ, maxQ);
                        }
                        if (typeof ytPlayer.setPlaybackQuality === 'function') {
                            ytPlayer.setPlaybackQuality(qMode === 'FORCE_1080P' ? 'hd1080' : minQ);
                        }

                        if (!ytPlayer.__rxlQualityListenerBound && typeof ytPlayer.addEventListener === 'function') {
                            ytPlayer.__rxlQualityListenerBound = true;
                            ytPlayer.addEventListener('onPlaybackQualityChange', function() {
                                enforceWebQuality();
                            });
                            ytPlayer.addEventListener('onStateChange', function() {
                                enforceWebQuality();
                            });
                        }
                    }

                    // HLS.js instance quality floor (>= 720p or highest available fallback)
                    var hlsInst = window.hls || (window.player && window.player.hls);
                    if (hlsInst && Array.isArray(hlsInst.levels) && hlsInst.levels.length > 0) {
                        var chosenIdx = -1;
                        var bestIdx = 0;
                        var bestHeight = -1;
                        for (var l = 0; l < hlsInst.levels.length; l++) {
                            var h = hlsInst.levels[l].height || 0;
                            if (h > bestHeight) {
                                bestHeight = h;
                                bestIdx = l;
                            }
                            if (qMode === 'FORCE_720P' && h === 720) chosenIdx = l;
                            else if (qMode === 'FORCE_1080P' && h === 1080) chosenIdx = l;
                            else if (qMode === 'AUTO_720P_MIN' && h >= 720 && h >= bestHeight) chosenIdx = l;
                        }
                        var finalLevel = (chosenIdx !== -1) ? chosenIdx : bestIdx;
                        if (hlsInst.currentLevel !== finalLevel) {
                            hlsInst.currentLevel = finalLevel;
                            hlsInst.loadLevel = finalLevel;
                        }
                    }
                } catch (e) {}
            }

            function pickBestVideo() {
                var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
                if (!vids.length) return null;
                var best = vids[0];
                var bestScore = -1;
                for (var i = 0; i < vids.length; i++) {
                    var v = vids[i];
                    var r = v.getBoundingClientRect();
                    var area = Math.max(1, r.width) * Math.max(1, r.height);
                    var bonus = (!v.paused ? 500000 : 0) + (v.readyState >= 2 ? 200000 : 0) + (v.videoWidth > 0 ? 300000 : 0);
                    if (area + bonus > bestScore) {
                        bestScore = area + bonus;
                        best = v;
                    }
                }
                return best;
            }

            function pickBestEmbedFrame() {
                var frames = Array.prototype.slice.call(document.querySelectorAll('iframe'));
                var best = null;
                var bestArea = 10000;
                for (var i = 0; i < frames.length; i++) {
                    var f = frames[i];
                    var src = (f.src || '').toLowerCase();
                    if (src.indexOf('ads') !== -1 || src.indexOf('doubleclick') !== -1 || src.indexOf('googlesyndication') !== -1) continue;
                    var r = f.getBoundingClientRect();
                    var area = r.width * r.height;
                    if (area > bestArea) {
                        bestArea = area;
                        best = f;
                    }
                }
                return best;
            }

            function dismissCommonOverlays() {
                try {
                    var modals = document.querySelectorAll('[role="dialog"] [aria-label*="Close" i], [data-e2e="modal-close-inner-button"]');
                    for (var j = 0; j < modals.length; j++) {
                        modals[j].click();
                    }
                } catch (e) {}
            }

            function syncMediaState() {
                try {
                    enforceWebQuality();

                    var styleEl = document.getElementById('__rxl_isolator_css');
                    if (!window.__rxlIsolate) {
                        if (styleEl) styleEl.remove();
                        var oldChain = document.querySelectorAll('[data-rxl-chain], [data-rxl-target]');
                        for (var k = 0; k < oldChain.length; k++) {
                            oldChain[k].removeAttribute('data-rxl-chain');
                            oldChain[k].removeAttribute('data-rxl-target');
                        }
                        var vPlain = pickBestVideo();
                        if (vPlain && !window.__rxlWasAcceleratingAd) {
                            vPlain.muted = !!window.__rxlDesiredMute;
                            vPlain.volume = window.__rxlDesiredMute ? 0.0 : (window.__rxlDesiredDuck ? 0.22 : 1.0);
                        }
                        return;
                    }

                    dismissCommonOverlays();

                    var target = pickBestVideo() || pickBestEmbedFrame();
                    if (!target) return;

                    var prev = document.querySelectorAll('[data-rxl-chain], [data-rxl-target]');
                    for (var c = 0; c < prev.length; c++) {
                        prev[c].removeAttribute('data-rxl-chain');
                        prev[c].removeAttribute('data-rxl-target');
                    }

                    target.setAttribute('data-rxl-target', '1');
                    var node = target.parentElement;
                    while (node && node !== document.documentElement) {
                        node.setAttribute('data-rxl-chain', '1');
                        node = node.parentElement;
                    }

                    if (!styleEl) {
                        styleEl = document.createElement('style');
                        styleEl.id = '__rxl_isolator_css';
                        (document.head || document.documentElement).appendChild(styleEl);
                    }

                    var desiredCss =
                        'html, body { margin: 0 !important; padding: 0 !important; width: 100vw !important; height: 100vh !important; overflow: hidden !important; background: #000 !important; } ' +
                        'body *:not([data-rxl-chain]):not([data-rxl-target]) { visibility: hidden !important; pointer-events: none !important; } ' +
                        '[data-rxl-chain] { visibility: visible !important; position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; max-width: none !important; max-height: none !important; margin: 0 !important; padding: 0 !important; border: 0 !important; transform: none !important; background: #000 !important; z-index: 2147483645 !important; overflow: hidden !important; } ' +
                        '[data-rxl-target] { visibility: visible !important; position: fixed !important; inset: 0 !important; left: 0 !important; top: 0 !important; width: 100vw !important; height: 100vh !important; max-width: none !important; max-height: none !important; margin: 0 !important; padding: 0 !important; border: 0 !important; transform: none !important; background: #000 !important; object-fit: ' + window.__rxlObjectFit + ' !important; z-index: 2147483647 !important; pointer-events: auto !important; }';

                    if (styleEl.textContent !== desiredCss) {
                        styleEl.textContent = desiredCss;
                    }

                    if (target.tagName === 'VIDEO' && !window.__rxlWasAcceleratingAd) {
                        target.loop = true;
                        target.playsInline = true;
                        target.muted = !!window.__rxlDesiredMute;
                        target.volume = window.__rxlDesiredMute ? 0.0 : (window.__rxlDesiredDuck ? 0.22 : 1.0);
                        if (window.__rxlDesiredPlay && target.paused) {
                            var p = target.play();
                            if (p && p.catch) {
                                p.catch(function() {
                                    target.muted = true;
                                    target.play().catch(function(){});
                                });
                            }
                        } else if (!window.__rxlDesiredPlay && !target.paused) {
                            target.pause();
                        }
                    }
                } catch (e) {}
            }

            syncMediaState();
            if (!window.__rxlIsolatorTimer) {
                window.__rxlIsolatorTimer = setInterval(syncMediaState, 650);
            }
        })();
        """.trimIndent()
    }

    /**
     * Controls embedded players (YouTube IFrame API + HTML5 <video>) inside SMART_EMBED mode,
     * including 720p+ quality range enforcement and AI voice audio ducking.
     */
    fun buildEmbedSyncJs(
        shouldPlay: Boolean,
        shouldMute: Boolean,
        isAudioDucked: Boolean,
        qualityPreference: VideoQualityPreference,
        scaleMode: DisplayScaleMode
    ): String {
        val fit = cssObjectFit(scaleMode)
        val ytCmd = if (shouldPlay) "playVideo" else "pauseVideo"
        val ytMute = if (shouldMute) "mute" else "unMute"
        val ytVol = if (shouldMute) 0 else if (isAudioDucked) 22 else 100
        val html5Vol = if (shouldMute) "0.0" else if (isAudioDucked) "0.22" else "1.0"
        val qualityModeName = qualityPreference.name

        return """
        (function() {
            try {
                if (typeof window.__rxlApplyYtQuality === 'function') {
                    window.__rxlApplyYtQuality('$qualityModeName');
                }
                if (window.__rxlYtPlayer && typeof window.__rxlYtPlayer.$ytCmd === 'function') {
                    window.__rxlYtPlayer.$ytCmd();
                    window.__rxlYtPlayer.$ytMute();
                    if (typeof window.__rxlYtPlayer.setVolume === 'function') {
                        window.__rxlYtPlayer.setVolume($ytVol);
                    }
                }
                var ifr = document.querySelector('iframe');
                if (ifr && ifr.contentWindow) {
                    ifr.contentWindow.postMessage(JSON.stringify({event:'command',func:'$ytCmd',args:[]}), '*');
                    ifr.contentWindow.postMessage(JSON.stringify({event:'command',func:'$ytMute',args:[]}), '*');
                    ifr.contentWindow.postMessage(JSON.stringify({event:'command',func:'setVolume',args:[$ytVol]}), '*');
                }
                var v = document.querySelector('video');
                if (v) {
                    v.style.objectFit = '$fit';
                    v.muted = ${if (shouldMute) "true" else "false"};
                    v.volume = $html5Vol;
                    ${if (shouldPlay) "v.play().catch(function(){});" else "v.pause();"}
                }
            } catch (e) {}
        })();
        """.trimIndent()
    }
}
