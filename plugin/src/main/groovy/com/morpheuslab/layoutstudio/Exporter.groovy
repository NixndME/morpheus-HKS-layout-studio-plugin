package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

/** Reads layouts and what they use and builds the backup file. */
@Slf4j
class Exporter {
    SelfApi api
    List<String> notes = []

    private Map<Long, Map> scripts = [:]
    private Map<Long, Map> templates = [:]
    private Map<Long, Map> nodeTypes = [:]
    private Map<String, Map> optionTypes = [:]
    private List<Map> builtinLayouts
    private Map<Long, String> packageCodes
    private Map<String, Map> addons = [:]      // your own add-ons by code, with their YAML
    private Map<String, Map> workflows = [:]   // workflows by name, with their steps

    Map export(List<Long> layoutIds, String user) {
        List layouts = layoutIds.collect { Long id -> layout(id) }.findAll()
        [format     : Bundle.FORMAT,
         version    : Bundle.VERSION,
         exportedAt : java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
         exportedBy : user,
         morpheus   : api.get('/api/ping').buildVersion,
         notes      : notes ?: null,
         layouts    : layouts,
         nodeTypes  : nodeTypes.values().findAll { it }.toList(),
         scripts    : scripts.values().findAll { it }.toList(),
         templates  : templates.values().findAll { it }.toList(),
         optionTypes: optionTypes.values().findAll { it }.toList(),
         addons     : addons.values().toList(),
         workflows  : workflows.values().toList()].findAll { k, v -> v != null && v != [] }
    }

    private Map layout(Long id) {
        Map l = api.get("/api/library/cluster-layouts/${id}").layout as Map
        if (!l) { notes << "Layout ${id} not found, skipped.".toString(); return null }
        List nodes = (l.computeServers ?: []).sort { it.priorityOrder ?: 0 }.collect { Map cs ->
            [role       : cs.nodeType,
             count      : cs.nodeCount,
             minCount   : cs.minNodeCount,
             maxCount   : cs.maxNodeCount,
             dynamic    : cs.dynamicCount,
             nameSuffix : cs.nameSuffix,
             nodeType   : nodeTypeRef(cs.containerType as Map)].findAll { k, v -> v != null }
        }
        (l.taskSets ?: []).each { workflow(it.id as Long) }
        if (l.specTemplates) notes << "Layout ${l.name}: spec templates ${l.specTemplates*.name.join(', ')} saved by name only.".toString()
        [name                  : l.name,
         description           : l.description,
         basedOn               : basedOn(l),
         clusterType           : l.groupType?.code,
         provisionType         : l.provisionType?.code,
         computeVersion        : l.computeVersion,
         clusterVersion        : l.clusterVersion,
         networkRuntime        : l.networkRuntime,
         containerRuntime      : l.containerRuntime,
         creatable             : l.creatable,
         hasAutoScale          : l.hasAutoScale,
         installContainerRuntime: l.installContainerRuntime,
         memoryRequirement     : l.memoryRequirement,
         sortOrder             : l.sortOrder,
         labels                : l.labels ?: null,
         environmentVariables  : (l.environmentVariables ?: []).collect { Map e ->
             [name: e.name ?: e.evarName, value: e.defaultValue ?: e.value, masked: e.masked, export: e.export] } ?: null,
         optionTypes           : (l.optionTypes ?: []).collect { optionTypeRef(it as Map) } ?: null,
         workflows             : l.taskSets*.name ?: null,
         specTemplates         : l.specTemplates*.name ?: null,
         packages              : packages(l.id as Long) ?: null,
         nodes                 : nodes].findAll { k, v -> v != null }
    }

    /** The built-in layout this one comes from. It gives the Kubernetes version and runtimes on restore. */
    private String basedOn(Map l) {
        if (!l.account) return l.code
        List<Map> same = builtins().findAll { Map b ->
            b.groupType?.code == l.groupType?.code && b.provisionType?.code == l.provisionType?.code &&
                nodeTypeIds(b) == nodeTypeIds(l) && (!l.clusterVersion || b.clusterVersion == l.clusterVersion)
        }
        if (same*.clusterVersion.unique().size() == 1 && same) return same.sort { it.code }.first().code
        if (same) notes << "Layout ${l.name}: set basedOn to one of ${same*.code.take(6).join(', ')}.".toString()
        null
    }

    private static List nodeTypeIds(Map l) { (l.computeServers ?: []).collect { it.containerType?.id }.findAll().sort() }

    private List<Map> builtins() {
        if (builtinLayouts == null) builtinLayouts = Catalog.list(api, '/api/library/cluster-layouts', 'layouts').findAll { !it.account }
        builtinLayouts
    }

    /** Add-on packages, read from the layout's clone form because the API does not show them. */
    private List<String> packages(Long id) {
        if (packageCodes == null) packageCodes = Catalog.list(api, '/api/library/cluster-packages', 'clusterPackages').collectEntries { [(it.id as Long): it.code as String] }
        String form = api.html("/library/cluster-layouts/${id}/clone")
        List<Long> ids = (form =~ /name="package\.id" value="(\d+)"/).collect { it[1] as Long }
        ids.each { addon(it) }
        ids.collect { packageCodes[it] }.findAll()
    }

    /** Your own add-ons are saved in full so they can be made again; built-in ones only by code. */
    private void addon(Long id) {
        Map p = api.get("/api/library/cluster-packages/${id}").clusterPackage as Map
        if (!p?.account || addons.containsKey(p.code)) return
        addons[p.code as String] = [code: p.code, name: p.name, version: p.packageVersion, type: p.type, packageType: p.packageType,
            description: p.description,
            yaml: (p.specTemplates ?: []).collect { Map s -> [name: s.name, content: api.get("/api/library/spec-templates/${s.id}").specTemplate?.file?.content ?: ''] }
        ].findAll { k, v -> v != null }
    }

    /** A layout workflow with its shell steps, so the extra steps come back too. */
    private void workflow(Long id) {
        Map ts = api.get("/api/task-sets/${id}").taskSet as Map
        if (!ts || workflows.containsKey(ts.name)) return
        List steps = ((ts.taskSetTasks ?: []) as List<Map>).collect { Map tt ->
            Map t = api.get("/api/tasks/${tt.task?.id}").task as Map
            if (t?.taskType?.code != 'script') { notes << "Workflow ${ts.name}: step ${t?.name} is not a shell step and is saved by name only.".toString() }
            [name: t?.name, phase: tt.taskPhase, type: t?.taskType?.code, sudo: t?.taskOptions?.get('shell.sudo') == 'on', content: t?.file?.content]
        }
        workflows[ts.name as String] = [name: ts.name, steps: steps]
    }

    private String nodeTypeRef(Map ct) {
        if (!ct?.id) return null
        Map t = api.get("/api/library/container-types/${ct.id}").containerType as Map ?: ct
        if (!t.account) return Bundle.builtin(t.code as String)
        if (!nodeTypes.containsKey(t.id as Long)) {
            nodeTypes[t.id as Long] = null   // avoid loops
            nodeTypes[t.id as Long] = [
                name                : t.name,
                shortName           : t.shortName,
                version             : t.containerVersion,
                category            : t.category,
                provisionType       : t.provisionType?.code,
                virtualImage        : t.virtualImage?.name,
                ports               : (t.containerPorts ?: []).collect { [name: it.name, port: it.port, protocol: it.loadBalanceProtocol].findAll { k, v -> v != null } } ?: null,
                scripts             : (t.containerScripts ?: []).collect { scriptRef(it.id as Long) },
                templates           : (t.containerTemplates ?: []).collect { templateRef(it.id as Long) },
                environmentVariables: (t.environmentVariables ?: []).collect { Map e ->
                    [name: e.name ?: e.evarName, value: e.defaultValue ?: e.value, masked: e.masked, export: e.export] } ?: null
            ].findAll { k, v -> v != null }
        }
        t.name as String
    }

    private String scriptRef(Long id) {
        Map s = api.get("/api/library/container-scripts/${id}").containerScript as Map
        if (!s) return null
        if (!s.account) return Bundle.builtin(s.code as String)
        scripts[id] = [name: s.name, phase: s.scriptPhase, type: s.scriptType, category: s.category,
                       runAsUser: s.runAsUser, sudo: s.sudoUser, failOnError: s.failOnError,
                       content: s.script].findAll { k, v -> v != null }
        s.name as String
    }

    private String templateRef(Long id) {
        Map t = api.get("/api/library/container-templates/${id}").containerTemplate as Map
        if (!t) return null
        if (!t.account) return Bundle.builtin(t.code as String)
        templates[id] = [name: t.name, fileName: t.fileName, filePath: t.filePath, phase: t.templatePhase,
                         category: t.category, autoRun: t.autoRun, runOnScale: t.runOnScale, runOnDeploy: t.runOnDeploy,
                         owner: t.fileOwner, group: t.fileGroup, permissions: t.permissions,
                         content: t.template].findAll { k, v -> v != null }
        t.name as String
    }

    /** Library option types are the user's own, saved in full. Built-in inputs are saved by code. */
    private String optionTypeRef(Map o) {
        Map lib = api.get("/api/library/option-types/${o.id}").optionType as Map
        if (!lib) return Bundle.builtin(o.code as String)
        optionTypes[lib.name as String] = [
            name: lib.name, description: lib.description, fieldName: lib.fieldName, fieldLabel: lib.fieldLabel,
            fieldContext: lib.fieldContext, fieldGroup: lib.fieldGroup, type: lib.type, required: lib.required,
            defaultValue: lib.defaultValue, placeHolder: lib.placeHolder, helpBlock: lib.helpBlock,
            displayOrder: lib.displayOrder, verifyPattern: lib.verifyPattern, exportMeta: lib.exportMeta,
            optionList: lib.optionList?.name, config: lib.config ?: null
        ].findAll { k, v -> v != null && v != '' }
        if (lib.optionList) notes << "Option type ${lib.name}: option list ${lib.optionList.name} saved by name only.".toString()
        lib.name as String
    }
}
