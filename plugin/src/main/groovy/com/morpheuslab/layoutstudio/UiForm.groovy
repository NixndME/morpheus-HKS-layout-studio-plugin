package com.morpheuslab.layoutstudio

/** A Morpheus UI form read from its HTML: the fields a browser would send, in order. */
class UiForm {
    String action
    List<List<String>> fields = []

    /** Reads the first form whose action starts with actionPrefix. */
    static UiForm parse(String html, String actionPrefix) {
        def fm = html =~ /(?is)<form\b([^>]*)>(.*?)<\/form>/
        for (def m : fm) {
            Map fa = attrs(m[1] as String)
            if (!(fa.action as String)?.startsWith(actionPrefix)) continue
            UiForm f = new UiForm(action: fa.action)
            def em = (m[2] as String) =~ /(?is)<input\b([^>]*)>|<select\b([^>]*)>(.*?)<\/select>|<textarea\b([^>]*)>(.*?)<\/textarea>/
            for (def e : em) {
                if (e[1] != null) {
                    Map a = attrs(e[1] as String)
                    String type = (a.type ?: 'text').toString().toLowerCase()
                    if (!a.name || type in ['submit', 'button', 'image', 'file', 'reset']) continue
                    if (type in ['checkbox', 'radio'] && !a.containsKey('checked')) continue
                    f.fields << [a.name as String, (a.value ?: (type in ['checkbox', 'radio'] ? 'on' : '')) as String]
                } else if (e[2] != null) {
                    Map a = attrs(e[2] as String)
                    if (!a.name) continue
                    def opts = (e[3] as String) =~ /(?is)<option\b([^>]*)>(.*?)<\/option>/
                    String chosen = null, first = null
                    for (def o : opts) {
                        Map oa = attrs(o[1] as String)
                        String v = oa.containsKey('value') ? oa.value as String : unescape((o[2] as String).trim())
                        if (first == null) first = v
                        if (oa.containsKey('selected')) chosen = v
                    }
                    if ((chosen ?: first) != null) f.fields << [a.name as String, chosen ?: first]
                } else if (e[4] != null) {
                    Map a = attrs(e[4] as String)
                    if (a.name) f.fields << [a.name as String, unescape(e[5] as String)]
                }
            }
            return f
        }
        null
    }

    List<String> all(String name) { fields.findAll { it[0] == name }.collect { it[1] } }
    String get(String name) { fields.find { it[0] == name }?.getAt(1) }

    /** Sets a single-value field, adding it when missing. */
    UiForm set(String name, Object value) {
        int i = fields.findIndexOf { it[0] == name }
        if (i >= 0) fields[i] = [name, value as String] else fields << [name, value as String]
        this
    }

    UiForm removeAll(String name) { fields.removeAll { it[0] == name }; this }
    UiForm add(String name, Object value) { fields << [name, value as String]; this }

    /** Changes the n-th value of a repeated field, for example the second node count. */
    UiForm setNth(String name, int n, Object value) {
        int seen = 0
        for (int i = 0; i < fields.size(); i++) {
            if (fields[i][0] == name && seen++ == n) { fields[i] = [name, value as String]; break }
        }
        this
    }

    private static Map attrs(String s) {
        Map out = [:]
        def am = s =~ /([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/
        for (def a : am) out[(a[1] as String).toLowerCase()] = unescape((a[2] ?: a[3] ?: a[4] ?: '') as String)
        out
    }

    static String unescape(String s) {
        s?.replace('&lt;', '<')?.replace('&gt;', '>')?.replace('&quot;', '"')?.replace('&#39;', "'")?.replace('&#x27;', "'")?.replace('&amp;', '&')
    }
}
