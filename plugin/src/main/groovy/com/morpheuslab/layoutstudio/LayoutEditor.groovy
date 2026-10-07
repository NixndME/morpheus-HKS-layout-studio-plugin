package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

/** Changes your own layouts through Morpheus' edit form, the only way that keeps the Kubernetes version and add-ons. */
@Slf4j
class LayoutEditor {
    SelfApi api
    Closure<Integer> fixTypes   // (node sets to fix), from the controller

    UiForm form(Long id) {
        UiForm.parse(api.html("/library/cluster-layouts/${id}/edit"), "/library/cluster-layouts/${id}")
    }

    /** Saves the form; puts back the node server types if the save changed them. Returns an error or null. */
    String save(Long id, UiForm f) {
        Map before = api.get("/api/library/cluster-layouts/${id}").layout as Map
        Map<String, String> codes = ((before?.computeServers ?: []) as List<Map>).collectEntries { [(it.nodeType as String): it.computeServerType?.code as String] }
        Map r = api.form(f.action, f.fields)
        if (r.status >= 400) return "Morpheus did not save the layout (HTTP ${r.status}).".toString()
        String why = (r.text =~ /has-error[\s\S]*?help-block[^>]*>\s*([^<]+)</).collect { it[1].trim() }.findAll().join(' ')
        if (why) return why
        Map after = api.get("/api/library/cluster-layouts/${id}").layout as Map
        List<Map> fix = ((after?.computeServers ?: []) as List<Map>).findAll { codes[it.nodeType as String] && it.computeServerType?.code != codes[it.nodeType as String] }
            .collect { [id: it.id, nodeTypeId: it.containerType?.id, serverType: codes[it.nodeType as String]] }
        if (fix && fixTypes) fixTypes.call(fix)
        null
    }

    /** Name, description and node counts (counts by role, in node set order). */
    String details(Long id, String name, String description, Map<String, Integer> counts) {
        UiForm f = form(id)
        if (!f) return 'Could not open the layout.'
        if (name?.trim()) f.set('computeTypeLayout.name', name.trim())
        f.set('computeTypeLayout.description', description ?: '')
        List<String> roles = f.all('computeTypeSets.nodeType')
        roles.eachWithIndex { String role, int i -> if (counts[role] != null) f.setNth('computeTypeSets.nodeCount', i, counts[role]) }
        save(id, f)
    }

    String addPackage(Long id, Long pkg) {
        UiForm f = form(id)
        if (!f) return 'Could not open the layout.'
        if (!(pkg as String in f.all('package.id'))) f.add('package.id', pkg)
        save(id, f)
    }

    String removePackage(Long id, Long pkg) {
        UiForm f = form(id)
        if (!f) return 'Could not open the layout.'
        List<String> keep = f.all('package.id') - [pkg as String]
        f.removeAll('package.id')
        keep.each { f.add('package.id', it) }
        save(id, f)
    }

    String setWorkflow(Long id, Long taskSetId) {
        UiForm f = form(id)
        if (!f) return 'Could not open the layout.'
        f.set('taskSetId', taskSetId ?: '')
        save(id, f)
    }

    /** Makes an existing layout match a layout from a backup file. Ids are already resolved for this Morpheus. */
    String applyFile(Long id, Map l, List<Long> nodeTypeIds, List<Long> optionTypeIds, List<Long> packageIds, Long workflowId) {
        UiForm f = form(id)
        if (!f) return 'Could not open the layout.'
        f.set('computeTypeLayout.name', l.name).set('computeTypeLayout.description', l.description ?: '')
        if (l.computeVersion) f.set('computeTypeLayout.computeVersion', l.computeVersion)
        f.set('computeTypeLayout.labelString', ((l.labels ?: []) as List).join(','))
        if (l.creatable == false) f.removeAll('computeTypeLayout.creatable') else f.set('computeTypeLayout.creatable', 'on')
        ['environmentVariable.id', 'environmentVariable.evarName', 'environmentVariable.value', 'environmentVariable._export', 'environmentVariable._masked'].each { f.removeAll(it) }
        // Morpheus' form handler needs at least one env var row, even an empty one
        (((l.environmentVariables ?: []) as List<Map>) ?: [[name: '', value: '']]).each { Map e ->
            f.add('environmentVariable.id', -1).add('environmentVariable.evarName', e.name).add('environmentVariable.value', e.value ?: '')
             .add('environmentVariable._export', '').add('environmentVariable._masked', '')
        }
        f.removeAll('optionType.id'); optionTypeIds.findAll().each { f.add('optionType.id', it) }
        f.removeAll('package.id'); packageIds.findAll().each { f.add('package.id', it) }
        f.set('taskSetId', workflowId ?: '')
        List<String> roles = f.all('computeTypeSets.nodeType')
        List<Map> nodes = (l.nodes ?: []) as List<Map>
        if (nodes*.role != roles) return "The node roles in the file (${nodes*.role.join(', ')}) do not match this layout (${roles.join(', ')}).".toString()
        nodes.eachWithIndex { Map n, int i ->
            f.setNth('computeTypeSets.nodeCount', i, n.count ?: 1)
            if (nodeTypeIds[i]) f.setNth('computeTypeSets.containerTypeId', i, nodeTypeIds[i])
        }
        save(id, f)
    }

    /** Makes your own copy of a layout with Morpheus' clone form. Returns [id] or [error]. */
    Map copy(Long sourceId, String name) {
        Map src = api.get("/api/library/cluster-layouts/${sourceId}").layout as Map
        UiForm f = UiForm.parse(api.html("/library/cluster-layouts/${sourceId}/clone"), '/library/cluster-layouts')
        if (!src || !f) return [error: 'Could not open that layout.']
        f.set('computeTypeLayout.name', name)
        api.form(f.action, f.fields)
        Map made = Catalog.list(api, '/api/library/cluster-layouts', 'layouts').findAll { it.account && it.name == name }.max { it.id as Long }
        if (!made) return [error: 'Morpheus did not make the copy.']
        Map<String, String> codes = ((src.computeServers ?: []) as List<Map>).collectEntries { [(it.nodeType as String): it.computeServerType?.code as String] }
        List<Map> fix = ((made.computeServers ?: []) as List<Map>).findAll { codes[it.nodeType as String] && it.computeServerType?.code != codes[it.nodeType as String] }
            .collect { [id: it.id, nodeTypeId: it.containerType?.id, serverType: codes[it.nodeType as String]] }
        if (fix && fixTypes) fixTypes.call(fix)
        [id: made.id as Long]
    }
}
