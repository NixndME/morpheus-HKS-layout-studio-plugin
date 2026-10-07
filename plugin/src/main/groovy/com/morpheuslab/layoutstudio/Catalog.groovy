package com.morpheuslab.layoutstudio

/** What this Morpheus already has. Built-in items by code, own items by name. */
class Catalog {
    Map<String, Map> builtinScripts = [:], ownScripts = [:]
    Map<String, Map> builtinTemplates = [:], ownTemplates = [:]
    Map<String, Map> builtinNodeTypes = [:], ownNodeTypes = [:]
    Map<String, Long> builtinOptionTypes = [:]
    Map<String, Map> ownOptionTypes = [:]
    Map<String, Map> ownLayouts = [:], builtinLayouts = [:]
    Map<String, Long> packages = [:]
    Map<String, Long> clusterTypes = [:]
    Map<String, Long> provisionTypes = [:]
    Map<String, Long> workflows = [:], specTemplates = [:], optionLists = [:], virtualImages = [:]
    SelfApi api                                    // for the lazy reads below; null in unit tests
    Map<String, List<Map>> addonSpecs = [:]        // add-on code to its spec templates [id, content]
    Map<String, List<Map>> workflowSteps = [:]     // workflow name to its steps [taskId, name, phase, content]

    /** Spec templates of an add-on that is already here, with their YAML. */
    List<Map> specsOf(String code) {
        if (addonSpecs.containsKey(code) || !api || !packages[code]) return addonSpecs[code]
        Map p = api.get("/api/library/cluster-packages/${packages[code]}").clusterPackage as Map
        addonSpecs[code] = ((p?.specTemplates ?: []) as List<Map>).collect { Map s -> [id: s.id as Long, content: api.get("/api/library/spec-templates/${s.id}").specTemplate?.file?.content ?: ''] }
    }

    /** Steps of a workflow that is already here, with their scripts. */
    List<Map> stepsOf(String name) {
        if (workflowSteps.containsKey(name) || !api || !workflows[name]) return workflowSteps[name]
        Map ts = api.get("/api/task-sets/${workflows[name]}").taskSet as Map
        workflowSteps[name] = ((ts?.taskSetTasks ?: []) as List<Map>).collect { Map tt ->
            Map t = api.get("/api/tasks/${tt.task?.id}").task as Map
            [taskId: t?.id as Long, name: t?.name, phase: tt.taskPhase, content: t?.file?.content ?: '']
        }
    }

    static Catalog load(SelfApi api) {
        Catalog c = new Catalog(api: api)
        split(list(api, '/api/library/container-scripts', 'containerScripts'), c.builtinScripts, c.ownScripts)
        split(list(api, '/api/library/container-templates', 'containerTemplates'), c.builtinTemplates, c.ownTemplates)
        split(list(api, '/api/library/container-types', 'containerTypes'), c.builtinNodeTypes, c.ownNodeTypes)
        // the option type library only has user-made inputs
        list(api, '/api/library/option-types', 'optionTypes').each { c.ownOptionTypes[it.name as String] = it }
        list(api, '/api/library/cluster-layouts', 'layouts').each { Map l ->
            if (l.account) c.ownLayouts[l.name as String] = l
            else c.builtinLayouts[l.code as String] = l
            if (l.groupType?.code) c.clusterTypes[l.groupType.code as String] = l.groupType.id as Long
            (l.optionTypes ?: []).each { Map o -> if (o.code) c.builtinOptionTypes.putIfAbsent(o.code as String, o.id as Long) }
        }
        list(api, '/api/library/cluster-packages', 'clusterPackages').each { c.packages[it.code as String] = it.id as Long }
        list(api, '/api/provision-types', 'provisionTypes').each { c.provisionTypes[it.code as String] = it.id as Long }
        list(api, '/api/task-sets', 'taskSets').each { c.workflows[it.name as String] = it.id as Long }
        list(api, '/api/library/spec-templates', 'specTemplates').each { c.specTemplates[it.name as String] = it.id as Long }
        list(api, '/api/library/option-type-lists', 'optionTypeLists').each { c.optionLists[it.name as String] = it.id as Long }
        c
    }

    private static void split(List<Map> items, Map<String, Map> builtin, Map<String, Map> own) {
        items.each { Map i -> i.account ? own.put(i.name as String, i) : builtin.put(i.code as String, i) }
    }

    /** Reads all pages of a list. */
    static List<Map> list(SelfApi api, String path, String key) {
        List<Map> out = []
        int offset = 0
        while (true) {
            Map r = api.get("${path}?max=500&offset=${offset}")
            List page = (r[key] ?: []) as List
            out.addAll(page)
            Long total = r.meta?.total as Long
            offset += page.size()
            if (!page || total == null || offset >= total) break
        }
        out
    }
}
