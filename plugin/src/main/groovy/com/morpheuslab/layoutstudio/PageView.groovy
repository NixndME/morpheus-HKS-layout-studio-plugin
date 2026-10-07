package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

/** Data for the page, ready to show. */
@Slf4j
class PageView {
    Long integrationId
    Map csrf
    Map message
    String access = 'none'
    String error
    List<Map> ownLayouts = []
    List<Map> builtinLayouts = []
    Map preview
    LayoutDetail detail

    boolean getCanExport() { access in ['read', 'full'] }
    boolean getCanImport() { access == 'full' }
    boolean getHasOwn() { !ownLayouts.isEmpty() }
    int getOwnCount() { ownLayouts.size() }
    int getBuiltinCount() { builtinLayouts.size() }
    boolean getIsError() { message?.type == 'error' }
    boolean getIsSuccess() { message?.type == 'success' }

    static PageView build(Long integrationId) {
        Object req = Req.current()
        PageView v = new PageView(integrationId: integrationId, csrf: Csrf.token())
        PanelState st = PanelState.get(Req.session(req))
        v.message = st.takeMessage()
        try {
            SelfApi api = SelfApi.of(req)
            v.access = accessOf(api)
            if (!v.canExport) return v
            // the page loads this HTML with its own request, so the open layout is kept in the session
            Long open = st.current()
            if (open) {
                v.detail = LayoutDetail.build(api, open)
                if (v.detail) return v
                st.openLayout = null
                v.error = 'That layout was not found.'
            }
            Catalog.list(api, '/api/library/cluster-layouts', 'layouts').each { Map l ->
                Map row = row(l)
                if (l.account) v.ownLayouts << row
                else if (l.groupType?.code == 'kubernetes-cluster') v.builtinLayouts << row
            }
            v.ownLayouts.sort { it.name.toLowerCase() }
            v.builtinLayouts.sort { a, b -> b.kube <=> a.kube ?: a.name <=> b.name }
            if (st.bundle) v.preview = preview(st)
        } catch (Exception e) {
            log.warn("HKS Layout Studio: page failed: ${e}")
            v.error = "Could not read the layouts: ${e.message}".toString()
        }
        v
    }

    /** The user's access to this plugin. */
    static String accessOf(SelfApi api) {
        Map w = api.get('/api/whoami')
        Object perms = w.permissions
        Map p = (perms instanceof List) ? ((List<Map>) perms).find { it.code == LayoutStudioPlugin.PERMISSION } : null
        (p?.access ?: 'none') as String
    }

    private static Map row(Map l) {
        List<Map> cs = (l.computeServers ?: []) as List<Map>
        int masters = cs.findAll { it.nodeType == 'master' }.sum { it.nodeCount ?: 0 } as Integer ?: 0
        int workers = cs.findAll { it.nodeType == 'worker' }.sum { it.nodeCount ?: 0 } as Integer ?: 0
        [id         : l.id,
         name       : l.name,
         type       : l.groupType?.name ?: '',
         provision  : l.provisionType?.name ?: '',
         kube       : l.clusterVersion ?: '-',
         os         : l.computeVersion ?: '',
         nodes      : "${masters} master${masters == 1 ? '' : 's'}, ${workers} worker${workers == 1 ? '' : 's'}".toString(),
         description: l.description ?: '']
    }

    private static final Map<String, String> CHIP = [create: 'new', update: 'overwrite', reuse: 'keep', blocked: 'blocked']

    private static Map preview(PanelState st) {
        List<Map> rows = st.steps.collect { Map s ->
            s + [label: CHIP[s.action as String] ?: s.action, chip: "hksl-chip-${s.action}".toString()]
        }
        Map<String, Integer> n = rows.countBy { it.action as String }
        [fileName   : st.fileName,
         exportedBy : st.bundle.exportedBy,
         exportedAt : st.bundle.exportedAt,
         morpheus   : st.bundle.morpheus,
         layouts    : (st.bundle.layouts as List<Map>)*.name.join(', '),
         names      : (st.bundle.layouts as List<Map>).withIndex().collect { Map l, int i -> [index: i, name: st.suggested[i] ?: l.name] },
         update     : st.update,
         rows       : rows,
         warnings   : st.warnings,
         notes      : st.bundle.notes ?: [],
         blocked    : rows.any { it.blocked },
         summary    : "${n.create ?: 0} new, ${n.update ?: 0} overwritten, ${n.reuse ?: 0} kept${n.blocked ? ", ${n.blocked} blocked" : ''}".toString()]
    }
}
