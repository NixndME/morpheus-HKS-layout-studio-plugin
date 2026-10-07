package com.morpheuslab.layoutstudio

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/** The backup file in YAML. Own items are saved in full, built-in items as builtin:<code>. */
class Bundle {
    static final String FORMAT = 'morpheus-cluster-layouts'
    static final int VERSION = 1
    static final String BUILTIN = 'builtin:'

    static boolean isBuiltin(Object ref) { ref instanceof String && ((String) ref).startsWith(BUILTIN) }
    static String builtinCode(String ref) { ref.substring(BUILTIN.length()) }
    static String builtin(String code) { BUILTIN + code }

    static String toYaml(Map bundle) {
        DumperOptions o = new DumperOptions()
        o.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        o.indent = 2
        o.width = 120
        o.splitLines = false
        Representer r = new Representer(o) {
            {
                // scripts and templates as readable text blocks
                this.representers.put(String, { Object data ->
                    String s = (String) data
                    representScalar(Tag.STR, s, s.contains('\n') ? DumperOptions.ScalarStyle.LITERAL : null)
                } as org.yaml.snakeyaml.representer.Represent)
            }
        }
        new Yaml(r, o).dump(bundle)
    }

    /** Reads one YAML or JSON file, or a zip of them. Returns [bundle] or [error]. */
    static Map read(byte[] data) {
        if (data.length < 4 || !(data[0] == ('P' as char) && data[1] == ('K' as char))) return parse(new String(data, 'UTF-8'))
        List<Map> all = []
        String err = null
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(data)).withCloseable { zip ->
            def e
            while ((e = zip.nextEntry) != null) {
                if (e.directory || e.name.contains('__MACOSX') || !(e.name ==~ /(?i).+\.(ya?ml|json)/)) continue
                Map r = parse(new String(zip.readAllBytes(), 'UTF-8'))
                if (r.error) { err = "${e.name}: ${r.error}".toString(); break }
                all << (Map) r.bundle
            }
        }
        if (err) return [error: err]
        if (!all) return [error: 'The zip file has no layout files.']
        [bundle: merge(all)]
    }

    /** Several backup files as one. Items shared by layouts are kept once. */
    static Map merge(List<Map> bundles) {
        if (bundles.size() == 1) return bundles[0]
        Map out = bundles[0].findAll { k, v -> !(v instanceof List) }
        Map<String, String> keyOf = [addons: 'code']
        ['layouts', 'nodeTypes', 'scripts', 'templates', 'optionTypes', 'addons', 'workflows', 'notes'].each { String list ->
            List items = bundles.collectMany { (it[list] ?: []) as List }
            out[list] = list == 'notes' ? items.unique() : items.unique { it instanceof Map ? it[keyOf[list] ?: 'name'] : it }
        }
        out.findAll { k, v -> v != null && v != [] }
    }

    /** Reads YAML or JSON. Returns [bundle] or [error]. */
    static Map parse(String text) {
        if (!text?.trim()) return [error: 'The file is empty.']
        Object data
        try {
            LoaderOptions lo = new LoaderOptions()
            lo.maxAliasesForCollections = 10
            lo.codePointLimit = 20 * 1024 * 1024
            data = new Yaml(new SafeConstructor(lo)).load(text)
        } catch (Exception e) {
            return [error: "This is not a valid YAML or JSON file: ${e.message?.take(200)}".toString()]
        }
        if (!(data instanceof Map)) return [error: 'This is not a layout backup file.']
        Map b = (Map) data
        if (b.format != FORMAT) return [error: "This is not a layout backup file. The format must be ${FORMAT}.".toString()]
        if (!(b.version instanceof Number) || ((Number) b.version).intValue() > VERSION) {
            return [error: "This file needs a newer version of the plugin.".toString()]
        }
        if (!(b.layouts instanceof List) || !b.layouts) return [error: 'The file has no layouts.']
        [bundle: b]
    }
}
