package com.morpheuslab.layoutstudio

/** Deletes one of your own layouts, and if asked, its own parts that no other layout uses. */
class LayoutRemover {
    SelfApi api

    /** Clusters built from this layout. A layout in use is not deleted. */
    List<String> clustersUsing(Long id) {
        Catalog.list(api, '/api/clusters', 'clusters').findAll { (it.layout?.id as Long) == id }.collect { it.name as String }
    }

    /** Own node types, scripts, files and workflow of this layout that no other layout uses. */
    Map parts(Map layout) {
        Long id = layout.id as Long
        List<Map> others = Catalog.list(api, '/api/library/cluster-layouts', 'layouts').findAll { (it.id as Long) != id }
        Set<Long> otherTypes = others.collectMany { l -> (l.computeServers ?: [])*.containerType*.id }.findAll().collect { it as Long } as Set
        Set<Long> otherFlows = others.collectMany { l -> (l.taskSets ?: [])*.id }.findAll().collect { it as Long } as Set

        List<Map> types = ((layout.computeServers ?: [])*.containerType*.id).findAll().collect { it as Long }.unique()
            .findAll { !(it in otherTypes) }.collect { api.get("/api/library/container-types/${it}").containerType as Map }
            .findAll { it && it.account != null }
        Set<Long> typeIds = types*.id.collect { it as Long } as Set

        // scripts and files go only when no other node type uses them
        List<Map> allTypes = Catalog.list(api, '/api/library/container-types', 'containerTypes').findAll { !((it.id as Long) in typeIds) }
        Set<Long> usedScripts = allTypes.collectMany { (it.containerScripts ?: [])*.id }.findAll().collect { it as Long } as Set
        Set<Long> usedFiles = allTypes.collectMany { (it.containerTemplates ?: [])*.id }.findAll().collect { it as Long } as Set
        List<Map> scripts = types.collectMany { (it.containerScripts ?: [])*.id }.findAll().collect { it as Long }.unique()
            .findAll { !(it in usedScripts) }.collect { api.get("/api/library/container-scripts/${it}").containerScript as Map }.findAll { it && it.account != null }
        List<Map> files = types.collectMany { (it.containerTemplates ?: [])*.id }.findAll().collect { it as Long }.unique()
            .findAll { !(it in usedFiles) }.collect { api.get("/api/library/container-templates/${it}").containerTemplate as Map }.findAll { it && it.account != null }

        List<Map> flows = ((layout.taskSets ?: [])*.id).findAll().collect { it as Long }.findAll { !(it in otherFlows) }
            .collect { api.get("/api/task-sets/${it}").taskSet as Map }.findAll()
        [types: types, scripts: scripts, files: files, flows: flows]
    }

    /** Deletes the layout, then its parts when asked. Returns [error] or [ok, lines]. */
    Map remove(Long id, boolean withParts) {
        Map layout = api.get("/api/library/cluster-layouts/${id}").layout as Map
        if (!layout) return [error: 'That layout was not found.']
        if (layout.account == null) return [error: 'Built-in layouts can not be deleted.']
        List<String> using = clustersUsing(id)
        if (using) return [error: "This layout is used by ${using.join(', ')}. Delete ${using.size() == 1 ? 'that cluster' : 'those clusters'} first.".toString()]
        Map p = withParts ? parts(layout) : [types: [], scripts: [], files: [], flows: []]

        Map r = api.delete("/api/library/cluster-layouts/${id}")
        if ((r._status as Integer) >= 300 || r.success == false) return [error: "Morpheus did not delete the layout: ${r.msg ?: r.errors ?: r._status}".toString()]

        List<String> lines = [], failed = []
        Closure gone = { String path, String what, Object name ->
            Map d = api.delete(path)
            if ((d._status as Integer) < 300 && d.success != false) lines << "${what}: ${name}".toString()
            else failed << "${what} ${name}".toString()
        }
        // the workflow and node types first, then the scripts and files they held
        p.flows.each { Map f ->
            List<Long> tasks = ((f.taskSetTasks ?: [])*.task*.id).findAll().collect { it as Long }
            gone("/api/task-sets/${f.id}", 'Extra steps workflow', f.name)
            tasks.each { Long t ->
                boolean shared = Catalog.list(api, '/api/task-sets', 'taskSets').any { s -> ((s.taskSetTasks ?: [])*.task*.id).collect { it as Long }.contains(t) }
                if (!shared) { Map task = api.get("/api/tasks/${t}").task as Map; if (task) gone("/api/tasks/${t}", 'Step', task.name) }
            }
        }
        p.types.each { Map t -> gone("/api/library/container-types/${t.id}", 'Node type', t.name) }
        p.scripts.each { Map s -> gone("/api/library/container-scripts/${s.id}", 'Script', s.name) }
        p.files.each { Map f -> gone("/api/library/container-templates/${f.id}", 'File', f.name) }
        if (failed) lines << "Could not delete, still in Morpheus: ${failed.join(', ')}".toString()
        [ok: "Layout deleted: ${layout.name}".toString(), lines: lines]
    }
}
