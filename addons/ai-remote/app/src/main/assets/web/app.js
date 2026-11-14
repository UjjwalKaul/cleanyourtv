// CleanYourTV Web Remote: Mobile Companion Client
(function () {
    "use strict";
    window.__APP_LOADED__ = true;

    // 1. Session Token & Connection State
    function getCookie(name) {
        try {
            const value = `; ${document.cookie}`;
            const parts = value.split(`; ${name}=`);
            if (parts.length === 2) return parts.pop().split(";").shift() || "";
        } catch (ignored) {}
        return "";
    }

    function getStoredToken() {
        try {
            return localStorage.getItem("tv_token") || "";
        } catch (ignored) {
            return "";
        }
    }

    function saveStoredToken(token) {
        try {
            localStorage.setItem("tv_token", token);
        } catch (ignored) {}
        try {
            document.cookie = `tv_token=${encodeURIComponent(token)}; path=/; max-age=31536000; SameSite=Lax`;
        } catch (ignored) {}
    }

    function clearStoredToken() {
        try {
            localStorage.removeItem("tv_token");
        } catch (ignored) {}
        try {
            document.cookie = "tv_token=; path=/; max-age=0";
        } catch (ignored) {}
    }

    const urlParams = new URLSearchParams(window.location.search);
    let sessionToken =
        urlParams.get("token") ||
        (window.__INJECTED_TOKEN__ &&
        window.__INJECTED_TOKEN__ !== "__TOKEN_PLACEHOLDER__"
            ? window.__INJECTED_TOKEN__
            : "") ||
        getCookie("tv_token") ||
        getStoredToken() ||
        "";

    if (sessionToken && sessionToken.length === 6) {
        sessionToken = sessionToken.toUpperCase();
        saveStoredToken(sessionToken);
        // Persist token in browser address bar without reload so page refresh retains it seamlessly
        if (!urlParams.has("token")) {
            try {
                const newUrl = `${window.location.pathname}?token=${encodeURIComponent(sessionToken)}${window.location.hash}`;
                window.history.replaceState({}, "", newUrl);
            } catch (ignored) {}
        }
    }

    let ws = null;
    let isConnected = false;
    let installedApps = [];
    let isMuted = false;
    let isPlaying = false;
    let reconnectAttempts = 0;
    let reconnectTimeout = null;

    // DOM Elements
    const statusDot = document.getElementById("statusDot");
    const statusText = document.getElementById("statusText");
    const appsShelf = document.getElementById("appsShelf");
    const tvTextInput = document.getElementById("tvTextInput");
    const btnSendText = document.getElementById("btnSendText");
    const inpPairingToken = document.getElementById("inpPairingToken");
    const btnUpdateToken = document.getElementById("btnUpdateToken");
    const btnForget = document.getElementById("btnForget");
    const pairingModal = document.getElementById("pairingModal");
    const modalTokenInput = document.getElementById("modalTokenInput");
    const btnModalConnect = document.getElementById("btnModalConnect");

    // 2. Navigation Tabs
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".tab-btn").forEach((b) => {
                b.classList.remove("active");
                b.setAttribute("aria-selected", "false");
            });
            document
                .querySelectorAll(".view")
                .forEach((v) => v.classList.remove("active"));
            btn.classList.add("active");
            btn.setAttribute("aria-selected", "true");
            const tabId = btn.getAttribute("data-tab");
            const targetView = document.getElementById("view-" + tabId);
            if (targetView) targetView.classList.add("active");
            triggerHaptic(8);
        });
    });

    const initialTab = new URLSearchParams(window.location.search).get("tab") || window.location.hash.replace("#", "");
    if (initialTab) {
        const initialBtn = document.querySelector(`.tab-btn[data-tab="${initialTab}"]`);
        if (initialBtn) initialBtn.click();
    }

    function detectDeviceName() {
        const ua = navigator.userAgent || "";
        if (/iPhone/i.test(ua)) return "Apple iPhone";
        if (/iPad/i.test(ua)) return "Apple iPad";
        if (/Pixel/i.test(ua)) return "Google Pixel";
        if (/Samsung|SM-/i.test(ua)) return "Samsung Phone";
        if (/Android/i.test(ua)) return "Android Device";
        if (/Macintosh/i.test(ua)) return "Apple Mac";
        if (/Windows/i.test(ua)) return "Windows PC";
        return "Mobile Remote";
    }

    // 3. WebSocket Connection & Pairing
    function connectWs() {
        if (!sessionToken || sessionToken.length !== 6) {
            showPairingModal();
            return;
        }

        if (
            ws &&
            (ws.readyState === WebSocket.OPEN ||
                ws.readyState === WebSocket.CONNECTING)
        )
            return;

        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}/ws?token=${encodeURIComponent(sessionToken)}`;

        statusDot.className = "status-dot";
        statusText.textContent = "Connecting…";

        try {
            ws = new WebSocket(wsUrl);

            ws.onopen = () => {
                isConnected = true;
                reconnectAttempts = 0;
                statusDot.className = "status-dot connected";
                statusText.textContent = "Connected";
                hidePairingModal();
                try {
                    ws.send(
                        JSON.stringify({
                            type: "HELLO",
                            deviceName: detectDeviceName(),
                        }),
                    );
                } catch (ignored) {}
            };

            ws.onmessage = (event) => {
                try {
                    const msg = JSON.parse(event.data);
                    handleServerMessage(msg);
                } catch (e) {
                    console.error("Failed to parse WebSocket message", e);
                }
            };

            ws.onclose = (event) => {
                isConnected = false;
                statusDot.className = "status-dot";
                statusText.textContent = "Disconnected";

                // Clear token only if server explicitly rejects auth
                if (event.code === 1008 || event.code === 4001) {
                    clearStoredToken();
                    sessionToken = "";
                    showPairingModal();
                    return;
                }

                reconnectAttempts++;
                const delay = Math.min(
                    800 * Math.pow(1.3, reconnectAttempts),
                    5000,
                );
                clearTimeout(reconnectTimeout);
                reconnectTimeout = setTimeout(connectWs, delay);
            };

            ws.onerror = () => {
                ws.close();
            };
        } catch (e) {
            setTimeout(connectWs, 2000);
        }
    }

    function showPairingModal() {
        if (pairingModal) {
            pairingModal.style.display = "flex";
            if (modalTokenInput) {
                modalTokenInput.value = "";
                modalTokenInput.focus();
            }
        }
    }

    function hidePairingModal() {
        if (pairingModal) pairingModal.style.display = "none";
    }

    if (btnModalConnect) {
        btnModalConnect.addEventListener("click", () => {
            const code = modalTokenInput.value.trim().toUpperCase();
            if (code.length === 6) {
                sessionToken = code;
                saveStoredToken(code);
                if (inpPairingToken) inpPairingToken.value = code;
                hidePairingModal();
                connectWs();
            } else {
                alert("Please enter a valid 6-character code from your TV screen.");
            }
        });
    }

    if (btnUpdateToken) {
        btnUpdateToken.addEventListener("click", () => {
            const code = inpPairingToken.value.trim().toUpperCase();
            if (code.length === 6) {
                sessionToken = code;
                saveStoredToken(code);
                if (ws) ws.close();
                connectWs();
                triggerHaptic(12);
                showToast("Pairing code updated");
            } else {
                alert("Please enter a 6-character code.");
            }
        });
    }

    function sendTvCommand(payload) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(payload));
            triggerHaptic(12);
        } else {
            triggerHaptic(40);
            statusText.textContent = "Offline (Reconnecting…)";
        }
    }

    function setMuteState(muted) {
        isMuted = !!muted;
        const btnMute = document.getElementById("btnMute");
        if (!btnMute) return;
        if (isMuted) {
            btnMute.classList.add("is-muted");
            btnMute.setAttribute("aria-label", "Unmute TV");
            btnMute.innerHTML = `
                <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor"></polygon>
                    <line x1="22" y1="9" x2="16" y2="15"></line>
                    <line x1="16" y1="9" x2="22" y2="15"></line>
                </svg>
                <span>Unmute</span>
            `;
        } else {
            btnMute.classList.remove("is-muted");
            btnMute.setAttribute("aria-label", "Mute TV");
            btnMute.innerHTML = `
                <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor"></polygon>
                    <path d="M15.5 8.5a5 5 0 0 1 0 7"></path>
                    <path d="M19 5a9.5 9.5 0 0 1 0 14"></path>
                </svg>
                <span>Mute</span>
            `;
        }
    }

    function updatePlayPauseUi(playing) {
        isPlaying = !!playing;
        const btn = document.getElementById("btnPlayPause");
        if (!btn) return;
        if (isPlaying) {
            btn.classList.add("is-playing");
            btn.setAttribute("aria-label", "Pause media");
            btn.innerHTML = `
                <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
                    <rect x="6" y="4" width="3.8" height="16" rx="1.9"></rect>
                    <rect x="14.2" y="4" width="3.8" height="16" rx="1.9"></rect>
                </svg>
                <span>Pause</span>
            `;
        } else {
            btn.classList.remove("is-playing");
            btn.setAttribute("aria-label", "Play media");
            btn.innerHTML = `
                <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
                    <path d="M7 4.5v15a1 1 0 0 0 1.55.84l12-7.5a1 1 0 0 0 0-1.68l-12-7.5A1 1 0 0 0 7 4.5z"></path>
                </svg>
                <span>Play</span>
            `;
        }
    }

    function handleServerMessage(msg) {
        if (typeof msg.isMuted === "boolean") {
            setMuteState(msg.isMuted);
        }
        if (msg.type === "APPS_LIST" || msg.type === "APPS_UPDATED") {
            installedApps = msg.apps || [];
            renderAppsShelf(installedApps);
        } else if (msg.status === "success" && (msg.launched || msg.name)) {
            const displayName = msg.launched || msg.name || "App";
            showToast(`Opened ${displayName} on TV`);
        } else if (msg.status === "error") {
            showToast(msg.message || "Action failed");
        }
    }

    // 4. Tactile Remote Controls with smooth hold-to-repeat
    let repeatTimer = null;
    let repeatInterval = null;
    let activeRepeatBtn = null;
    let lastMuteToggle = 0;
    let lastPlayToggle = 0;

    function stopRepeat() {
        clearTimeout(repeatTimer);
        clearInterval(repeatInterval);
        repeatTimer = null;
        repeatInterval = null;
        if (activeRepeatBtn) {
            activeRepeatBtn.classList.remove("is-active");
            activeRepeatBtn = null;
        }
    }

    document.querySelectorAll("[data-key]").forEach((btn) => {
        const code = btn.getAttribute("data-key");
        const isRepeatable =
            code.startsWith("DPAD_") || code.startsWith("VOLUME_");

        let handledByPointer = false;
        let pointerResetTimer = null;

        function triggerPress() {
            const now = Date.now();
            if (code === "MUTE") {
                if (now - lastMuteToggle < 350) return;
                lastMuteToggle = now;
                if (isMuted) {
                    sendTvCommand({ type: "KEY", code: "UNMUTE" });
                    setMuteState(false);
                    showToast("Unmuted TV");
                } else {
                    sendTvCommand({ type: "KEY", code: "MUTE" });
                    setMuteState(true);
                    showToast("Muted TV");
                }
                return;
            }
            if (code === "PLAY_PAUSE") {
                if (now - lastPlayToggle < 350) return;
                lastPlayToggle = now;
                sendTvCommand({ type: "KEY", code: "PLAY_PAUSE" });
                isPlaying = !isPlaying;
                updatePlayPauseUi(isPlaying);
                showToast(isPlaying ? "Play" : "Pause");
                return;
            }
            if (code === "VOLUME_UP" || code === "VOLUME_DOWN") {
                setMuteState(false);
            }
            sendTvCommand({ type: "KEY", code: code });
        }

        btn.addEventListener("pointerdown", () => {
            handledByPointer = true;
            clearTimeout(pointerResetTimer);
            stopRepeat();
            activeRepeatBtn = btn;
            btn.classList.add("is-active");
            triggerPress();

            if (isRepeatable) {
                repeatTimer = setTimeout(() => {
                    repeatInterval = setInterval(() => {
                        triggerPress();
                    }, 110);
                }, 350);
            }
        });

        const handlePointerEnd = () => {
            stopRepeat();
            clearTimeout(pointerResetTimer);
            pointerResetTimer = setTimeout(() => {
                handledByPointer = false;
            }, 400);
        };

        btn.addEventListener("pointerup", handlePointerEnd);
        btn.addEventListener("pointercancel", handlePointerEnd);
        btn.addEventListener("pointerleave", handlePointerEnd);

        btn.addEventListener("click", (e) => {
            e.preventDefault();
            if (handledByPointer) {
                return;
            }
            triggerPress();
        });
    });

    function triggerHaptic(ms) {
        if (navigator.vibrate) {
            try {
                navigator.vibrate(ms);
            } catch (ignored) {}
        }
    }

    let toastTimer = null;
    function showToast(message, iconSvg = null) {
        let toast = document.getElementById("appToast");
        if (!toast) {
            toast = document.createElement("div");
            toast.id = "appToast";
            toast.className = "app-toast";
            document.body.appendChild(toast);
        }
        if (iconSvg) {
            toast.innerHTML = `<span class="toast-icon" style="display:inline-flex;align-items:center;line-height:0;flex-shrink:0;">${iconSvg}</span><span>${escapeHtml(message)}</span>`;
        } else {
            toast.innerHTML = `<span>${escapeHtml(message)}</span>`;
        }
        toast.classList.add("show");
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            toast.classList.remove("show");
        }, 2200);
    }

    function getAppIconSvg(name, pkg) {
        const lowerName = (name || "").toLowerCase();
        const lowerPkg = (pkg || "").toLowerCase();

        if (lowerName.includes("youtube") || lowerPkg.includes("youtube") || lowerName.includes("smarttube") || lowerPkg.includes("videomanager")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="#EF4444"><path d="M22.54 6.42a2.78 2.78 0 0 0-1.94-2C18.88 4 12 4 12 4s-6.88 0-8.6.46a2.78 2.78 0 0 0-1.94 2A29 29 0 0 0 1 11.75a29 29 0 0 0 .46 5.33A2.78 2.78 0 0 0 3.4 19c1.72.46 8.6.46 8.6.46s6.88 0 8.6-.46a2.78 2.78 0 0 0 1.94-2 29 29 0 0 0 .46-5.25 29 29 0 0 0-.46-5.33z"/><polygon points="9.75 15.02 15.5 11.75 9.75 8.48 9.75 15.02" fill="#FFFFFF"/></svg>`;
        }
        if (lowerName.includes("netflix") || lowerPkg.includes("netflix")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24"><path d="M5.5 2.5h3.2v19H5.5z" fill="#B91C1C"/><path d="M15.3 2.5h3.2v19h-3.2z" fill="#B91C1C"/><path d="M5.5 2.5h3.5l6.3 19h-3.5z" fill="#E50914"/></svg>`;
        }
        if (lowerName.includes("setting") || lowerPkg.includes("setting")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#94A3B8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>`;
        }
        if (lowerName.includes("spotify") || lowerPkg.includes("spotify")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="#1DB954"><circle cx="12" cy="12" r="10"/><path d="M7.5 9.5c3.5-1 7.2-.7 10 1M8 12.5c2.8-.8 5.8-.5 8 1M8.5 15.5c2.3-.5 4.6-.3 6.5.8" stroke="#121212" stroke-width="1.8" stroke-linecap="round" fill="none"/></svg>`;
        }
        if (lowerName.includes("music") || lowerPkg.includes("music") || lowerName.includes("audio")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#F43F5E" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`;
        }
        if (lowerName.includes("prime") || lowerPkg.includes("amazon")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#00A8E1" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="6 4 18 12 6 20 6 4" fill="#00A8E1" stroke="none"/><path d="M4 20c4.5 2 11.5 2 16-2" stroke="#00A8E1" stroke-width="2"/></svg>`;
        }
        if (lowerName.includes("play store") || lowerPkg.includes("vending")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24"><path d="M3.6 2.5c-.3.4-.4.9-.4 1.5v16c0 .6.1 1.1.4 1.5l.1.1 9-9v-.2l-9.1-8.9z" fill="#00D2FF"/><path d="M15.7 14.6l-3-3v-.2l3-3 .1.1 3.5 2c1 .6 1 1.5 0 2.1l-3.6 2z" fill="#FFCE00"/><path d="M12.7 11.4L3.6 20.5c.4.4.9.4 1.5.1l10.6-6-3-3.2z" fill="#FF334B"/><path d="M12.7 11.4l3-3.2-10.6-6c-.6-.3-1.1-.3-1.5.1l9.1 9.1z" fill="#00E676"/></svg>`;
        }
        if (lowerName.includes("vlc") || lowerPkg.includes("videolan")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24"><polygon points="12 2 7 19 17 19" fill="#F97316"/><line x1="4" y1="21" x2="20" y2="21" stroke="#F97316" stroke-width="2.5" stroke-linecap="round"/><line x1="8.5" y1="13" x2="15.5" y2="13" stroke="#FFFFFF" stroke-width="2"/><line x1="10" y1="8" x2="14" y2="8" stroke="#FFFFFF" stroke-width="1.8"/></svg>`;
        }
        if (lowerName.includes("plex") || lowerName.includes("kodi") || lowerName.includes("jellyfin")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#E5A00D" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="4"/><polygon points="10 8 16 12 10 16 10 8" fill="#E5A00D"/></svg>`;
        }
        if (lowerName.includes("twitch") || lowerPkg.includes("twitch")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="#9333EA"><path d="M4.5 3h15v11.5l-4.5 4.5h-4l-2 2H6.5v-2h-2z" fill="#9333EA"/><circle cx="10" cy="10" r="1.2" fill="#FFFFFF"/><circle cx="15" cy="10" r="1.2" fill="#FFFFFF"/></svg>`;
        }
        if (lowerName.includes("browser") || lowerName.includes("chrome") || lowerPkg.includes("browser")) {
            return `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#38BDF8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>`;
        }
        const initial =
            name && name.length > 0 ? name.charAt(0).toUpperCase() : "?";
        return `<span style="font-weight:700;font-size:17px;color:var(--accent-cyan);">${escapeHtml(initial)}</span>`;
    }

    function renderAppsShelf(apps) {
        if (!appsShelf) return;
        appsShelf.innerHTML = "";
        if (!apps || apps.length === 0) {
            appsShelf.innerHTML =
                '<span style="grid-column:1/-1;text-align:center;color:var(--text-muted);font-size:13px;padding:16px;">No apps found</span>';
            return;
        }

        apps.forEach((app) => {
            const card = document.createElement("div");
            card.className = "app-card";
            card.setAttribute("role", "button");
            card.setAttribute("tabindex", "0");
            card.setAttribute("aria-label", `Launch ${app.name}`);

            const icon = document.createElement("div");
            icon.className = "app-card-icon";
            icon.innerHTML = getAppIconSvg(app.name, app.package);

            const label = document.createElement("div");
            label.className = "app-card-name";
            label.textContent = app.name;

            card.appendChild(icon);
            card.appendChild(label);

            let startX = 0;
            let startY = 0;
            let isScrolling = false;
            let pressTimer = null;

            card.addEventListener(
                "touchstart",
                (e) => {
                    isScrolling = false;
                    if (e.touches && e.touches.length > 0) {
                        startX = e.touches[0].clientX;
                        startY = e.touches[0].clientY;
                    }
                    pressTimer = setTimeout(() => {
                        if (!isScrolling) card.classList.add("is-pressed");
                    }, 60);
                },
                { passive: true },
            );

            card.addEventListener(
                "touchmove",
                (e) => {
                    if (e.touches && e.touches.length > 0) {
                        const dx = Math.abs(e.touches[0].clientX - startX);
                        const dy = Math.abs(e.touches[0].clientY - startY);
                        if (dx > 8 || dy > 8) {
                            isScrolling = true;
                            if (pressTimer) clearTimeout(pressTimer);
                            card.classList.remove("is-pressed");
                        }
                    }
                },
                { passive: true },
            );

            card.addEventListener("touchend", () => {
                if (pressTimer) clearTimeout(pressTimer);
                card.classList.remove("is-pressed");
            }, { passive: true });

            card.addEventListener("touchcancel", () => {
                if (pressTimer) clearTimeout(pressTimer);
                card.classList.remove("is-pressed");
            }, { passive: true });

            function launch() {
                if (isScrolling) return;
                triggerHaptic(15);
                showToast(`Launching ${app.name} on TV…`);
                sendTvCommand({ type: "LAUNCH_APP", package: app.package, name: app.name });
            }

            card.addEventListener("click", (e) => {
                if (isScrolling) {
                    e.preventDefault();
                    return;
                }
                launch();
            });

            card.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    launch();
                }
            });

            appsShelf.appendChild(card);
        });
    }

    // 5. TV Keyboard Text Input
    function sendTextToTv() {
        if (!tvTextInput) return;
        const text = tvTextInput.value.trim();
        if (!text) return;
        triggerHaptic(15);
        showToast("Sending text to TV…");
        sendTvCommand({ type: "TEXT_INPUT", text: text });
        tvTextInput.value = "";
    }

    if (btnSendText) {
        btnSendText.addEventListener("click", sendTextToTv);
    }
    if (tvTextInput) {
        tvTextInput.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                sendTextToTv();
            }
        });
    }

    // 6. Settings & Pairing
    if (btnForget) {
        btnForget.addEventListener("click", () => {
            clearStoredToken();
            sessionToken = "";
            try {
                window.history.replaceState({}, "", window.location.pathname);
            } catch (ignored) {}
            if (inpPairingToken) inpPairingToken.value = "";
            if (ws) ws.close();
            triggerHaptic(25);
            showToast("Pairing cleared");
            showPairingModal();
        });
    }

    if (sessionToken && inpPairingToken) {
        inpPairingToken.value = sessionToken;
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    // Initialize Connection
    connectWs();
})();
