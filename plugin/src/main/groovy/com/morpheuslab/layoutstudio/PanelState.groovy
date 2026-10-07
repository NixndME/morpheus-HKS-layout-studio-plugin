package com.morpheuslab.layoutstudio

import java.util.concurrent.ConcurrentHashMap

/** Per login session: the file waiting for restore and the last message. Kept 30 minutes. */
class PanelState {
    String fileName
    Long openLayout   // layout shown as a flow, null for the list
    long openUntil    // the flow stays open for a short time only, so the page opens on the list later

    void open(Long id) { openLayout = id; openUntil = System.currentTimeMillis() + 20000L }
    Long current() { openLayout && System.currentTimeMillis() < openUntil ? openLayout : null }
    Map bundle
    boolean update
    List<Map> steps = []
    List<String> suggested = []   // "Save as" names offered in the preview
    List<String> warnings = []
    String flash, flashType = 'info'
    List<String> details = []
    long touched = System.currentTimeMillis()

    private static final Map<String, PanelState> STATES = new ConcurrentHashMap<>()
    private static final long TTL = 30 * 60 * 1000L

    static PanelState get(String session) {
        long now = System.currentTimeMillis()
        STATES.entrySet().removeIf { now - it.value.touched > TTL }
        PanelState s = STATES.computeIfAbsent(session ?: 'none') { new PanelState() }
        s.touched = now
        s
    }

    static void clear(String session) { STATES.remove(session ?: 'none') }

    void message(String type, String text, List<String> lines = []) { flashType = type; flash = text; details = lines ?: [] }

    /** A message is shown once. */
    Map takeMessage() {
        Map m = flash ? [type: flashType, text: flash, details: details] : null
        flash = null; details = []
        m
    }
}

/** The current web request. */
class Req {
    static Object current() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            return rch.getMethod('getRequestAttributes').invoke(null)?.getRequest()
        } catch (Throwable ignored) {
            return null
        }
    }

    static String session(Object request) {
        try { return request?.getSession(true)?.id as String } catch (Throwable ignored) { return null }
    }
}
