package com.morpheuslab.layoutstudio

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/** Export, preview and restore. Each route checks access itself. */
@Slf4j
class LayoutsController implements PluginController {

    static final int MAX_FILE = 5 * 1024 * 1024

    Plugin plugin
    MorpheusContext morpheus

    LayoutsController(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-layout-studio-controller' }
    String getName() { 'HKS Layout Studio Controller' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    List<Route> getRoutes() {
        Permission read = Permission.build(LayoutStudioPlugin.PERMISSION, 'read')
        Permission full = Permission.build(LayoutStudioPlugin.PERMISSION, 'full')
        [Route.build('/hks-layout-studio/export', 'export', read),
         Route.build('/hks-layout-studio/preview', 'preview', full),
         Route.build('/hks-layout-studio/restore', 'restore', full),
         Route.build('/hks-layout-studio/cancel', 'cancel', full),
         Route.build('/hks-layout-studio/open', 'open', read),
         Route.build('/hks-layout-studio/edit', 'edit', full)]
    }

    /** Downloads the selected layouts as one YAML file. */
    def export(ViewModel<Map> model) {
        def req = model.request
        List<Long> ids = (req.getParameterValues('id') ?: []).collect { it as Long }
        if (!Access.canExport(model.user)) return denied(model, 'export')
        if (!ids) return back(model, 'error', 'Select at least one layout.')
        SelfApi api = SelfApi.of(req)
        String stamp = java.time.LocalDate.now().toString()
        // one layout gives one YAML file; several give a zip with one complete YAML file per layout
        List<Map> bundles = ids.collect { Long id -> new Exporter(api: api).export([id], model.user?.username) }.findAll { it.layouts }
        if (!bundles) return back(model, 'error', 'Could not read the selected layouts.')
        byte[] data
        String fileName, type
        if (bundles.size() == 1) {
            data = Bundle.toYaml(bundles[0]).getBytes('UTF-8')
            fileName = "${slug(bundles[0].layouts[0].name as String)}-${stamp}.yaml"
            type = 'application/x-yaml; charset=UTF-8'
        } else {
            ByteArrayOutputStream buf = new ByteArrayOutputStream()
            Set<String> used = [] as Set
            new java.util.zip.ZipOutputStream(buf).withCloseable { zip ->
                bundles.each { Map b ->
                    String n = slug(b.layouts[0].name as String), entry = "${n}.yaml"
                    int i = 2
                    while (!used.add(entry)) entry = "${n}-${i++}.yaml"
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry))
                    zip.write(Bundle.toYaml(b).getBytes('UTF-8'))
                    zip.closeEntry()
                }
            }
            data = buf.toByteArray()
            fileName = "cluster-layouts-${bundles.size()}-${stamp}.zip"
            type = 'application/zip'
        }
        def res = model.response
        res.setContentType(type)
        res.setHeader('Content-Disposition', "attachment; filename=\"${fileName}\"")
        res.setContentLength(data.length)
        res.outputStream.write(data)
        res.outputStream.flush()
        log.info("HKS Layout Studio: ${model.user?.username} exported layouts ${ids}")
        null
    }

    /** Reads the uploaded file and shows the changes. Nothing changes yet. */
    def preview(ViewModel<Map> model) {
        def req = model.request
        if (!post(req) || !Access.canImport(model.user)) return denied(model, 'preview')
        PanelState st = PanelState.get(Req.session(req))
        byte[] data = upload(req)
        if (data == null) return back(model, 'error', 'Choose a backup file first.')
        if (data.length > MAX_FILE) return back(model, 'error', 'The file is larger than 5 MB.')
        Map parsed = Bundle.read(data)
        if (parsed.error) return back(model, 'error', parsed.error as String)
        st.fileName = uploadName(req) ?: 'backup file'
        st.bundle = parsed.bundle as Map
        st.update = req.getParameter('update') == 'on'
        Catalog cat = Catalog.load(SelfApi.of(req))
        Importer im = new Importer(st.bundle, cat, st.update).plan()
        st.steps = im.steps*.toMap()
        st.warnings = im.warnings
        // a built-in layout comes back as your own copy, so suggest a copy name
        Set<String> builtinNames = cat.builtinLayouts.values()*.name as Set
        st.suggested = (st.bundle.layouts as List<Map>).collect { Map l -> l.name in builtinNames ? "${l.name} copy".toString() : l.name as String }
        backTo(model)
    }

    /** Restores the previewed file. Checks again first in case something changed. */
    def restore(ViewModel<Map> model) {
        def req = model.request
        if (!post(req) || !Access.canImport(model.user)) return denied(model, 'restore')
        PanelState st = PanelState.get(Req.session(req))
        if (!st.bundle) return back(model, 'error', 'Nothing to restore. Choose the file again.')
        // "Save as": a new name makes a new layout
        (st.bundle.layouts as List<Map>).eachWithIndex { Map l, int i ->
            String nm = (req.getParameter("as_${i}") ?: '').toString().trim()
            if (nm) l.name = nm
        }
        SelfApi api = SelfApi.of(req)
        Importer im = new Importer(st.bundle, Catalog.load(api), st.update).plan()
        if (im.blocked) {
            st.steps = im.steps*.toMap(); st.warnings = im.warnings
            return back(model, 'error', 'Some items are blocked. Nothing changed.')
        }
        im.afterClone = { List<Map> sets -> ServerTypeFix.apply(morpheus, sets) }
        Map r = im.apply(api)
        String file = st.fileName
        st.bundle = null; st.steps = []; st.warnings = []
        log.info("HKS Layout Studio: ${model.user?.username} restored ${file}: ok=${r.ok} ${r.changes ?: r.error}")
        if (r.ok) {
            List<String> lines = ((r.changes ?: []) as List<String>) + im.warnings.findAll { it.contains('server type') }
            return back(model, 'success', lines ? "Restore done." : "Nothing to restore. Everything is already here.", lines)
        }
        List<String> lines = ["Undone: ${r.rolledBack} new items removed.".toString()]
        if (r.leftBehind) lines << "Could not remove: ${r.leftBehind.join(', ')}".toString()
        back(model, 'error', "Restore failed: ${r.error}", lines)
    }

    /** Shows one layout as a flow, or the list again without a layout id. */
    def open(ViewModel<Map> model) {
        if (!Access.canExport(model.user)) return denied(model, 'open')
        String id = model.request?.getParameter('layout')
        PanelState.get(Req.session(model.request)).open(id?.isLong() ? id as Long : null)
        backTo(model)
    }

    /** Every change to a layout: details, add-ons, steps, copy, delete. Only your own layouts can be changed. */
    def edit(ViewModel<Map> model) {
        def req = model.request
        if (!post(req) || !Access.canImport(model.user)) return denied(model, 'edit')
        PanelState st = PanelState.get(Req.session(req))
        Long id = req.getParameter('layout')?.toString()?.isLong() ? req.getParameter('layout') as Long : null
        String what = req.getParameter('do') as String
        SelfApi api = SelfApi.of(req)
        Map layout = id ? api.get("/api/library/cluster-layouts/${id}").layout as Map : null
        if (!layout) return back(model, 'error', 'That layout was not found.')
        st.open(id)
        if (what != 'copy' && !layout.account) return back(model, 'error', 'Built-in layouts can not be changed. Make a copy first.')
        LayoutEditor ed = new LayoutEditor(api: api, fixTypes: { List<Map> sets -> ServerTypeFix.apply(morpheus, sets) })
        Steps steps = new Steps(api: api, editor: ed)
        Map r
        try {
            r = doEdit(what, req, id, layout, api, ed, steps)
        } catch (Exception e) {
            log.warn("HKS Layout Studio: edit ${what} on layout ${id} failed: ${e}")
            r = [error: "That did not work: ${e.message?.take(200)}".toString()]
        }
        log.info("HKS Layout Studio: ${model.user?.username} ${what} on layout ${id}: ${r.error ?: 'ok'}")
        if (r.open) st.open(r.open as Long)
        if (r.close) st.open(null)
        r.error ? back(model, 'error', r.error as String) : back(model, 'success', r.ok as String, (r.lines ?: []) as List<String>)
    }

    private Map doEdit(String what, Object req, Long id, Map layout, SelfApi api, LayoutEditor ed, Steps steps) {
        // Browsers send text boxes with \r\n; scripts must have Linux line endings
        Closure<String> param = { String n -> (req.getParameter(n) ?: '').toString().replace('\r\n', '\n').replace('\r', '\n') }
        Closure<Long> num = { String n -> param(n).isLong() ? param(n) as Long : null }
        switch (what) {
            case 'details':
                Map<String, Integer> counts = ['master', 'worker'].findAll { param("count_${it}").isInteger() }.collectEntries { [(it): param("count_${it}") as Integer] }
                if (counts.values().any { it < 1 || it > 50 }) return [error: 'Node counts must be between 1 and 50.']
                return wrap(ed.details(id, param('name'), param('description'), counts), 'Layout saved.')
            case 'delete':
                Map del = new LayoutRemover(api: api).remove(id, param('withParts') == 'on')
                return del.error ? del : del + [close: true]
            case 'copy':
                String name = param('name').trim() ?: "${layout.name} copy"
                Map c = ed.copy(id, name)
                return c.error ? c : [ok: "Copy made: ${name}. You can change it now.".toString(), open: c.id]
            case 'addon-existing':
                return wrap(ed.addPackage(id, num('packageId')), 'Add-on added.')
            case 'addon-remove':
                return wrap(ed.removePackage(id, num('packageId')), 'Add-on removed from this layout.')
            case 'addon-yaml':
                List<List> files = uploads(req, 'files')
                if (!files) return [error: 'Choose one or more YAML files.']
                String yaml = AddonMaker.join(files.collectEntries { [(it[0]): new String(it[1] as byte[], 'UTF-8')] })
                return addAddon(api, ed, id, param('name') ?: baseName(files[0][0] as String), param('version'), yaml, "From YAML file ${files*.getAt(0).join(', ')}", [])
            case 'addon-helm':
                List<List> chart = uploads(req, 'chart')
                if (!chart) return [error: 'Choose a Helm chart file (.tgz).']
                List<List> values = uploads(req, 'values')
                File tmp = File.createTempFile('hksl-chart', '.tgz')
                try {
                    tmp.bytes = chart[0][1] as byte[]
                    return helmAddon(api, ed, id, layout, tmp, values ? new String(values[0][1] as byte[], 'UTF-8') : null, param('name'), param('version'), param('namespace'), "From Helm chart ${chart[0][0]}")
                } finally { tmp.delete() }
            case 'addon-git':
                Map g = GitSource.fetch(param('url').trim(), param('ref').trim(), param('path').trim(), param('user').trim(), param('token'), param('skipTls') == 'on')
                try {
                    if (g.error) return [error: g.error]
                    String src = "From ${param('url').trim()}${param('ref') ? ' (' + param('ref').trim() + ')' : ''}${param('path') ? ' ' + param('path').trim() : ''}"
                    if (g.chartDir || g.chartFile) return helmAddon(api, ed, id, layout, (g.chartDir ?: g.chartFile) as File, param('values') ?: null, param('name'), param('version'), param('namespace'), src)
                    return addAddon(api, ed, id, param('name') ?: baseName(param('path') ?: param('url')), param('version'), AddonMaker.join(g.yaml as Map), src, [])
                } finally { (g.work as File)?.deleteDir() }
            case 'step-add':
                String content = param('content')
                List<List> up = uploads(req, 'stepFile')
                if (up && !content.trim()) content = new String(up[0][1] as byte[], 'UTF-8')
                String err = param('target') == 'workflow'
                    ? steps.addWorkflowStep(id, param('phase'), param('name'), content)
                    : steps.addToNodeType(num('nodeTypeId'), param('kind'), param('phase'), param('name'), content, param('fileName'), param('filePath'))
                return wrap(err, 'Step added.')
            case 'step-remove':
                return wrap(param('target') == 'workflow' ? steps.removeWorkflowStep(id, num('itemId')) : steps.removeFromNodeType(num('nodeTypeId'), param('kind'), num('itemId')), 'Step removed.')
            case 'step-save':
                return wrap(param('target') == 'workflow' ? steps.updateWorkflowStep(num('itemId'), param('content')) : steps.updateContent(param('kind'), num('itemId'), param('content')), 'Saved.')
            default:
                return [error: 'Unknown change.']
        }
    }

    private static Map wrap(String err, String ok) { err ? [error: err] : [ok: ok] }

    private Map addAddon(SelfApi api, LayoutEditor ed, Long id, String name, String version, String yaml, String source, List<String> warnings) {
        Map a = AddonMaker.create(api, name, version, yaml, source)
        if (a.error) return a
        String err = ed.addPackage(id, a.packageId as Long)
        if (err) return [error: "The add-on was saved but not added to the layout: ${err}".toString()]
        List<String> lines = warnings + (a.images ? ["Images it needs: ${(a.images as List).join(', ')}".toString(), 'Put these images in your local registry for offline sites.'] : [])
        [ok: "Add-on ${a.name} added.".toString(), lines: lines]
    }

    private Map helmAddon(SelfApi api, LayoutEditor ed, Long id, Map layout, File chart, String values, String name, String version, String namespace, String source) {
        Map info = Helm.info(chart)
        if (info.error) return info
        String nm = name?.trim() ?: info.name
        String ns = LayoutsController.slug(namespace?.trim() ?: nm)
        Map h = Helm.render(chart, LayoutsController.slug(nm), ns, values, (layout.clusterVersion as String)?.replace('.x', '.0'))
        if (h.error) return h
        String yaml = (ns == 'default' ? '' : "apiVersion: v1\nkind: Namespace\nmetadata:\n  name: ${ns}\n---\n") + h.yaml
        addAddon(api, ed, id, nm, version?.trim() ?: info.version, yaml, "${source}, chart ${info.name} ${info.version}", (h.warnings ?: []) as List<String>)
    }

    private static String baseName(String path) { (path ?: 'add-on').tokenize('/').last().replaceAll(/(?i)\.(ya?ml|tgz)$/, '') }

    /** Uploaded files of one form field, as [file name, bytes]. */
    private static List<List> uploads(Object req, String field) {
        List<List> out = []
        try { req.getFiles(field)?.each { f -> if (f && !f.empty) out << [f.originalFilename, f.bytes] } } catch (Throwable ignored) { }
        if (!out) {
            try { req.getParts()?.findAll { it.name == field && it.size > 0 }?.each { out << [it.submittedFileName, it.inputStream.bytes] } } catch (Throwable ignored) { }
        }
        out.findAll { (it[1] as byte[]).length <= MAX_FILE }
    }

    def cancel(ViewModel<Map> model) {
        if (!post(model.request) || !Access.canImport(model.user)) return denied(model, 'cancel')
        PanelState st = PanelState.get(Req.session(model.request))
        st.bundle = null; st.steps = []; st.warnings = []
        backTo(model)
    }

    // ---------- helpers ----------

    private static boolean post(Object req) { 'POST'.equalsIgnoreCase(req?.method as String) }

    private static byte[] upload(Object req) {
        try { def f = req.getFile('file'); if (f && !f.empty) return f.bytes } catch (Throwable ignored) { }
        try { def p = req.getPart('file'); if (p && p.size > 0) return p.inputStream.bytes } catch (Throwable ignored) { }
        null
    }

    private static String uploadName(Object req) {
        try { return req.getFile('file')?.originalFilename } catch (Throwable ignored) { }
        try { return req.getPart('file')?.submittedFileName } catch (Throwable ignored) { }
        null
    }

    private def denied(ViewModel<Map> model, String what) {
        log.warn("HKS Layout Studio: DENIED ${model.user?.username} ${what}")
        back(model, 'error', 'You do not have permission. Ask your admin.')
    }

    private def back(ViewModel<Map> model, String type, String text, List<String> lines = []) {
        PanelState.get(Req.session(model.request)).message(type, text, lines)
        backTo(model)
    }

    private def backTo(ViewModel<Map> model) {
        String id = model.request?.getParameter('integrationId')
        String url = id?.isLong() ? "/admin/integrations/${id}" : '/admin/integrations'
        try {
            def resp = model.response
            if (resp != null && !resp.committed) {
                resp.sendRedirect(url)
                return HTMLResponse.success('')   // a null return makes Morpheus log an error page warning
            }
        } catch (Throwable ignored) { }
        HTMLResponse.success("<!doctype html><html><head><meta http-equiv=\"refresh\" content=\"0;url=${url}\"></head><body><a href=\"${url}\">Back</a></body></html>")
    }

    static String slug(String s) { (s ?: 'layout').toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-|-$/, '') ?: 'layout' }
}
