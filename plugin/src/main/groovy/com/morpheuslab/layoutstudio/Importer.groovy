package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

/** Restores a backup file. plan() shows the changes, apply() makes them and undoes them on error. */
@Slf4j
class Importer {

    static class Step {
        String kind, name, action, detail
        Long existingId
        boolean isBlocked() { action == 'blocked' }
        Map toMap() { [kind: kind, name: name, action: action, detail: detail, blocked: blocked] }
    }

    Map bundle
    Catalog cat
    boolean update
    List<Step> steps = []
    List<String> warnings = []
    Closure<Integer> afterClone   // (node sets to fix), set by the controller

    Importer(Map bundle, Catalog cat, boolean update) { this.bundle = bundle; this.cat = cat; this.update = update }

    List<Map> items(String key) { (bundle[key] ?: []) as List<Map> }
    boolean isBlocked() { steps.any { it.blocked } }

    // ---------- plan ----------

    Importer plan() {
        items('scripts').each { Map s ->
            Map ex = cat.ownScripts[s.name as String]
            steps << decide('Script', s.name, ex, ex && same(ex.script, s.content) && ex.scriptPhase == s.phase)
        }
        items('templates').each { Map t ->
            Map ex = cat.ownTemplates[t.name as String]
            steps << decide('File template', t.name, ex, ex && same(ex.template, t.content) && ex.fileName == t.fileName && ex.filePath == t.filePath)
        }
        items('optionTypes').each { Map o ->
            Map ex = cat.ownOptionTypes[o.name as String]
            Step st = decide('Option type', o.name, ex, ex && ex.fieldName == o.fieldName && ex.type == o.type && ex.fieldLabel == o.fieldLabel)
            if (o.optionList && !cat.optionLists[o.optionList as String] && st.action in ['create', 'update']) {
                warnings << "Option type ${o.name}: option list ${o.optionList} not found, restored without it.".toString()
            }
            steps << st
        }
        items('nodeTypes').each { Map n -> steps << planNodeType(n) }
        items('addons').each { Map a ->
            Long ex = cat.packages[a.code as String]
            List<Map> have = ex ? cat.specsOf(a.code as String) : null
            boolean same = have != null && have*.content.collect { (it ?: '').trim() } == ((a.yaml ?: []) as List<Map>).collect { (it.content ?: '').trim() }
            steps << decide('Add-on', a.name, ex ? [id: ex] : null, same)
        }
        items('workflows').each { Map w ->
            Long ex = cat.workflows[w.name as String]
            List<Map> have = ex ? cat.stepsOf(w.name as String) : null
            Closure norm = { List l -> (l ?: []).collect { [it.name, it.phase, (it.content ?: '').trim()] } }
            Step st = decide('Workflow', w.name, ex ? [id: ex] : null, have != null && norm(have) == norm(w.steps as List))
            if (st.action in ['create', 'update'] && ((w.steps ?: []) as List<Map>).any { it.type && it.type != 'script' }) st = block(st, 'Only shell steps can be made again.')
            steps << st
        }
        items('layouts').each { Map l -> steps << planLayout(l) }
        this
    }

    private Step planNodeType(Map n) {
        Map ex = cat.ownNodeTypes[n.name as String]
        Step st = decide('Node type', n.name, ex, ex && ex.containerVersion == n.version &&
            (ex.containerScripts*.name ?: []) == ownNames(n.scripts) && (ex.containerTemplates*.name ?: []) == ownNames(n.templates))
        if (st.action == 'reuse') return st
        List<String> builtins = ((n.scripts ?: []) + (n.templates ?: [])).findAll { Bundle.isBuiltin(it) }
        if (builtins) return block(st, "Uses built-in ${builtins.collect { Bundle.builtinCode(it) }.join(', ')}. Morpheus cannot add built-in scripts to a new node type.")
        List<String> missing = ownNames(n.scripts).findAll { !named('scripts', it) } + ownNames(n.templates).findAll { !named('templates', it) }
        if (missing) return block(st, "Needs ${missing.join(', ')}, which is not in the file.")
        if (n.provisionType && !cat.provisionTypes[n.provisionType as String]) return block(st, "Provision type ${n.provisionType} not found here.")
        if (n.virtualImage) warnings << "Node type ${n.name}: set the virtual image ${n.virtualImage} by hand.".toString()
        st
    }

    private Step planLayout(Map l) {
        Map ex = cat.ownLayouts[l.name as String]
        Step st = decide('Layout', l.name, ex, ex != null && !update)
        if (st.action == 'reuse') return st
        if (!cat.clusterTypes[l.clusterType as String]) return block(st, "Cluster type ${l.clusterType} not found here.")
        if (!cat.provisionTypes[l.provisionType as String]) return block(st, "Provision type ${l.provisionType} not found here.")
        for (Map node : (l.nodes ?: []) as List<Map>) {
            String ref = node.nodeType
            if (Bundle.isBuiltin(ref) && !cat.builtinNodeTypes[Bundle.builtinCode(ref)]) return block(st, "Built-in node type ${Bundle.builtinCode(ref)} not found here. Is this a different Morpheus version?")
            if (!Bundle.isBuiltin(ref) && !named('nodeTypes', ref)) return block(st, "Node type ${ref} is not in the file.")
            if (!(node.role in ['master', 'worker'])) return block(st, "Node role ${node.role} is not supported.")
        }
        (l.optionTypes ?: []).each { String ref ->
            if (Bundle.isBuiltin(ref) && !cat.builtinOptionTypes[Bundle.builtinCode(ref)]) warnings << "Layout ${l.name}: input ${Bundle.builtinCode(ref)} not found, skipped.".toString()
        }
        (l.workflows ?: []).each { if (!cat.workflows[it as String] && !named('workflows', it as String)) warnings << "Layout ${l.name}: workflow ${it} not found, skipped.".toString() }
        (l.specTemplates ?: []).each { if (!cat.specTemplates[it as String]) warnings << "Layout ${l.name}: spec template ${it} not found, skipped.".toString() }
        if (l.basedOn && !cat.builtinLayouts[l.basedOn as String]) return block(st, "Built-in layout ${l.basedOn} not found here. Is this a different Morpheus version?")
        if (!l.basedOn && l.clusterType == 'kubernetes-cluster') {
            warnings << "Layout ${l.name} has no basedOn, so it gets no Kubernetes version or add-ons. New clusters from it will fail.".toString()
        }
        (l.packages ?: []).each { String code -> if (!cat.packages[code] && !items('addons').any { it.code == code }) warnings << "Layout ${l.name}: add-on ${code} not found, skipped.".toString() }
        st
    }

    private Step decide(String kind, Object name, Map existing, boolean same) {
        if (!existing) return new Step(kind: kind, name: name as String, action: 'create')
        if (same) return new Step(kind: kind, name: name as String, action: 'reuse', detail: 'Already here and the same', existingId: existing.id as Long)
        if (update) return new Step(kind: kind, name: name as String, action: 'update', detail: 'Already here, will be overwritten', existingId: existing.id as Long)
        new Step(kind: kind, name: name as String, action: 'reuse', detail: 'Already here but different, kept as it is', existingId: existing.id as Long)
    }

    private static Step block(Step st, String why) { st.action = 'blocked'; st.detail = why; st }
    private static List<String> ownNames(Object refs) { ((refs ?: []) as List).findAll { !Bundle.isBuiltin(it) }.collect { it as String } }
    private boolean named(String key, String name) { items(key).any { it.name == name } }
    // Morpheus trims spaces at the end of scripts
    private static boolean same(Object a, Object b) { (a ?: '').toString().trim() == (b ?: '').toString().trim() }

    // ---------- apply ----------

    /** Makes the changes. On error removes what it created. */
    Map apply(SelfApi api) {
        if (blocked) return [ok: false, error: 'Some items are blocked. Nothing changed.']
        Map<String, Map<String, Long>> ids = [scripts: [:], templates: [:], optionTypes: [:], nodeTypes: [:], layouts: [:]]
        List<List> created = []   // [path, id] in the order created
        List<String> done = []
        try {
            items('scripts').each { Map s ->
                ids.scripts[s.name] = write(api, step('Script', s.name), '/api/library/container-scripts', 'containerScript', [
                    name: s.name, scriptPhase: s.phase ?: 'provision', scriptType: s.type ?: 'bash', category: s.category,
                    runAsUser: s.runAsUser, sudoUser: s.sudo, failOnError: s.failOnError, script: s.content ?: ''], created, done)
            }
            items('templates').each { Map t ->
                ids.templates[t.name] = write(api, step('File template', t.name), '/api/library/container-templates', 'containerTemplate', [
                    name: t.name, fileName: t.fileName, filePath: t.filePath, templatePhase: t.phase ?: 'provision', category: t.category,
                    autoRun: t.autoRun, runOnScale: t.runOnScale, runOnDeploy: t.runOnDeploy, fileOwner: t.owner, fileGroup: t.group,
                    permissions: t.permissions, template: t.content ?: ''], created, done)
            }
            items('optionTypes').each { Map o ->
                Map body = o.findAll { k, v -> !(k in ['optionList']) }
                Long list = cat.optionLists[o.optionList as String]
                if (list) body.optionList = [id: list]
                ids.optionTypes[o.name] = write(api, step('Option type', o.name), '/api/library/option-types', 'optionType', body, created, done)
            }
            items('nodeTypes').each { Map n ->
                ids.nodeTypes[n.name] = write(api, step('Node type', n.name), '/api/library/container-types', 'containerType', [
                    name: n.name, shortName: n.shortName ?: n.name, containerVersion: n.version ?: '1', category: n.category,
                    provisionTypeCode: n.provisionType,
                    scripts: ownNames(n.scripts).collect { ids.scripts[it] },
                    templates: ownNames(n.templates).collect { ids.templates[it] },
                    containerPorts: n.ports ? n.ports.collect { [name: it.name, port: it.port, loadBalanceProtocol: it.protocol] } : null,
                    environmentVariables: evars(n.environmentVariables)], created, done)
            }
            items('addons').each { Map a ->
                Step st = step('Add-on', a.name)
                if (st.action == 'update') {
                    List<Map> have = cat.specsOf(a.code as String) ?: []
                    List<Map> want = (a.yaml ?: []) as List<Map>
                    want.eachWithIndex { Map y, int i ->
                        if (i >= have.size()) throw new IllegalStateException("Add-on ${a.name}: the file has more YAML parts than Morpheus.".toString())
                        String err = AddonMaker.updateYaml(api, have[i].id as Long, y.content as String)
                        if (err) throw new IllegalStateException("Add-on ${a.name}: ${err}".toString())
                    }
                    done << "Updated add-on '${a.name}'".toString()
                    return
                }
                if (st.action != 'create') return
                Map r = AddonMaker.restore(api, a)
                if (r.error) throw new IllegalStateException(r.error as String)
                created << ['/api/library/spec-templates', r.specId] << ['/api/library/cluster-packages', r.packageId]
                cat.packages[a.code as String] = r.packageId as Long
                done << "Created add-on '${a.name}'".toString()
            }
            items('workflows').each { Map w ->
                Step st = step('Workflow', w.name)
                if (st.action == 'update') { updateWorkflow(api, w); done << "Updated workflow '${w.name}'".toString(); return }
                if (st.action != 'create') return
                List<Map> tasks = []
                ((w.steps ?: []) as List<Map>).each { Map s ->
                    Map t = api.post('/api/tasks', [task: [name: Steps.uniqueTaskName(api, s.name as String), taskType: [code: 'script'], executeTarget: 'resource',
                        file: [sourceType: 'local', content: s.content ?: ''], taskOptions: s.sudo ? ['shell.sudo': 'on'] : [:]]])
                    Long tid = t.task?.id as Long
                    if (!tid) throw new IllegalStateException("Workflow ${w.name}: step ${s.name}: ${t.msg ?: t.errors}".toString())
                    created << ['/api/tasks', tid]
                    tasks << [taskId: tid, taskPhase: s.phase]
                }
                Map r = api.post('/api/task-sets', [taskSet: [name: w.name, type: 'provision', tasks: tasks]])
                Long wid = r.taskSet?.id as Long
                if (!wid) throw new IllegalStateException("Workflow ${w.name}: ${r.msg ?: r.errors}".toString())
                created << ['/api/task-sets', wid]
                cat.workflows[w.name as String] = wid
                done << "Created workflow '${w.name}' with ${tasks.size()} steps".toString()
            }
            items('layouts').each { Map l ->
                Step st = step('Layout', l.name)
                if (st.action == 'update') {
                    ids.layouts[l.name] = st.existingId
                    String err = new LayoutEditor(api: api, fixTypes: afterClone).applyFile(st.existingId, l,
                        ((l.nodes ?: []) as List<Map>).collect { Map n -> nodeTypeId(n.nodeType as String, ids) },
                        ((l.optionTypes ?: []) as List<String>).collect { String ref -> Bundle.isBuiltin(ref) ? cat.builtinOptionTypes[Bundle.builtinCode(ref)] : ids.optionTypes[ref] },
                        ((l.packages ?: []) as List).collect { cat.packages[it as String] },
                        ((l.workflows ?: []) as List).collect { cat.workflows[it as String] }.find())
                    if (err) throw new IllegalStateException("Layout ${l.name}: ${err}".toString())
                    done << "Updated layout '${l.name}'".toString()
                    return
                }
                ids.layouts[l.name] = (l.basedOn && st.action == 'create') ? cloneCreate(api, st, l, ids, created, done)
                    : write(api, st, '/api/library/cluster-layouts', 'layout', layoutBody(l, ids), created, done)
            }
            log.info("HKS Layout Studio: restore done: ${done.size()} changes")
            [ok: true, changes: done]
        } catch (Exception e) {
            log.warn("HKS Layout Studio: restore failed, rolling back ${created.size()} items: ${e.message}")
            List<String> left = []
            created.reverse().each { List c ->
                Map r = api.delete("${c[0]}/${c[1]}")
                if (r._status >= 400 || r.success == false) left << "${c[0]}/${c[1]}".toString()
            }
            [ok: false, error: e.message, rolledBack: created.size() - left.size(), leftBehind: left]
        }
    }

    /** Makes an existing workflow match the file: changed scripts, new steps, removed steps, order. */
    private void updateWorkflow(SelfApi api, Map w) {
        Long wf = cat.workflows[w.name as String]
        List<Map> have = cat.stepsOf(w.name as String) ?: []
        List<Map> tasks = []
        ((w.steps ?: []) as List<Map>).each { Map s ->
            Map ex = have.find { it.name == s.name }
            if (ex) {
                if ((ex.content ?: '').trim() != (s.content ?: '').trim()) {
                    Map r = api.put("/api/tasks/${ex.taskId}", [task: [file: [sourceType: 'local', content: s.content ?: '']]])
                    if (r.success == false) throw new IllegalStateException("Step ${s.name}: ${r.msg}".toString())
                }
                tasks << [taskId: ex.taskId, taskPhase: s.phase]
            } else {
                Map t = api.post('/api/tasks', [task: [name: Steps.uniqueTaskName(api, s.name as String), taskType: [code: 'script'], executeTarget: 'resource',
                    file: [sourceType: 'local', content: s.content ?: ''], taskOptions: s.sudo ? ['shell.sudo': 'on'] : [:]]])
                if (!t.task?.id) throw new IllegalStateException("Step ${s.name}: ${t.msg ?: t.errors}".toString())
                tasks << [taskId: t.task.id as Long, taskPhase: s.phase]
            }
        }
        Map r = api.put("/api/task-sets/${wf}", [taskSet: [tasks: tasks]])
        if (r.success == false) throw new IllegalStateException("Workflow ${w.name}: ${r.msg}".toString())
        have.findAll { Map h -> !tasks.any { it.taskId == h.taskId } }.each { api.delete("/api/tasks/${it.taskId}") }
    }

    private Long nodeTypeId(String ref, Map<String, Map<String, Long>> ids) {
        Bundle.isBuiltin(ref) ? cat.builtinNodeTypes[Bundle.builtinCode(ref)]?.id as Long : ids.nodeTypes[ref]
    }

    private Map layoutBody(Map l, Map<String, Map<String, Long>> ids) {
        List<Map> nodes = (l.nodes ?: []) as List<Map>
        Closure set = { Map n ->
            String ref = n.nodeType
            Long ct = Bundle.isBuiltin(ref) ? cat.builtinNodeTypes[Bundle.builtinCode(ref)]?.id as Long : ids.nodeTypes[ref]
            [nodeCount: n.count ?: 1, minNodeCount: n.minCount, maxNodeCount: n.maxCount, dynamicCount: n.dynamic,
             nameSuffix: n.nameSuffix, containerType: [id: ct]].findAll { k, v -> v != null }
        }
        Map body = [
            name: l.name, description: l.description, computeVersion: l.computeVersion, creatable: l.creatable,
            hasAutoScale: l.hasAutoScale, memoryRequirement: l.memoryRequirement, sortOrder: l.sortOrder, labels: l.labels,
            groupType: [id: cat.clusterTypes[l.clusterType as String]], provisionType: [id: cat.provisionTypes[l.provisionType as String]],
            environmentVariables: evars(l.environmentVariables),
            optionTypes: ((l.optionTypes ?: []) as List<String>).collect { String ref ->
                Bundle.isBuiltin(ref) ? cat.builtinOptionTypes[Bundle.builtinCode(ref)] : ids.optionTypes[ref] }.findAll().collect { [id: it] },
            taskSets: ((l.workflows ?: []) as List).collect { cat.workflows[it as String] }.findAll().collect { [id: it] },
            specTemplates: ((l.specTemplates ?: []) as List).collect { cat.specTemplates[it as String] }.findAll().collect { [id: it] },
            masters: nodes.findAll { it.role == 'master' }.collect(set),
            workers: nodes.findAll { it.role == 'worker' }.collect(set)]
        body.findAll { k, v -> v != null }
    }

    /** Creates the layout with Morpheus' clone form, the only way that keeps the Kubernetes version and add-ons. */
    private Long cloneCreate(SelfApi api, Step st, Map l, Map<String, Map<String, Long>> ids, List<List> created, List<String> done) {
        Map src = cat.builtinLayouts[l.basedOn as String]
        Long mem = (l.memoryRequirement ?: 0) as Long
        List<List> f = [['cloneSourceId', src.id],
            ['computeTypeLayout.name', l.name], ['computeTypeLayout.computeVersion', l.computeVersion ?: src.computeVersion],
            ['computeTypeLayout.description', l.description], ['computeTypeLayout.labelString', (l.labels ?: []).join(',')],
            ['computeTypeLayout.sortOrder', l.sortOrder ?: 0], ['computeTypeLayout._creatable', '']]
        if (l.creatable != false) f << ['computeTypeLayout.creatable', 'on']
        f += [['computeTypeLayout.groupType.id', cat.clusterTypes[l.clusterType as String]],
              ['computeTypeLayout.provisionType.code', l.provisionType],
              ['computeTypeLayout.memoryRequirement', mem > 0 ? (mem / 1048576L) as Long : 0], ['config.memorySizeType', 'mb'],
              ['taskSetId', ((l.workflows ?: []) as List).collect { cat.workflows[it as String] }.find() ?: ''],
              ['computeTypeLayout._hasAutoScale', '']]
        if (l.hasAutoScale) f << ['computeTypeLayout.hasAutoScale', 'on']
        f << ['_installContainerRuntime', '']
        if (l.installContainerRuntime) f << ['installContainerRuntime', 'on']
        f << ['_installStorageRuntime', '']
        // Morpheus' form handler needs at least one env var row, even an empty one, like its own form sends
        List<Map> evs = ((l.environmentVariables ?: []) as List<Map>) ?: [[name: '', value: '']]
        evs.each { Map e ->
            f += [['environmentVariable.id', -1], ['environmentVariable.evarName', e.name], ['environmentVariable.value', e.value],
                  ['environmentVariable._export', ''], ['environmentVariable._masked', '']]
        }
        ((l.optionTypes ?: []) as List<String>).each { String ref ->
            Long id = Bundle.isBuiltin(ref) ? cat.builtinOptionTypes[Bundle.builtinCode(ref)] : ids.optionTypes[ref]
            if (id) f << ['optionType.id', id]
        }
        ((l.nodes ?: []) as List<Map>).eachWithIndex { Map n, int i ->
            String ref = n.nodeType
            Long ct = Bundle.isBuiltin(ref) ? cat.builtinNodeTypes[Bundle.builtinCode(ref)]?.id as Long : ids.nodeTypes[ref]
            f += [['computeTypeSets.nodeType', n.role], ['computeTypeSets.containerTypeId', ct],
                  ['computeTypeSets.nodeCount', n.count ?: 1], ['computeTypeSets.priorityOrder', i]]
        }
        ((l.packages ?: []) as List).each { Long p = cat.packages[it as String]; if (p) f << ['package.id', p] }
        Map r = api.form('/library/cluster-layouts', f)
        Map made = Catalog.list(api, '/api/library/cluster-layouts', 'layouts').findAll { it.account && it.name == l.name }.max { it.id as Long }
        if (!made) {
            String why = (r.text =~ /has-error[\s\S]*?help-block[^>]*>\s*([^<]+)</).collect { it[1].trim() }.findAll().join(' ')
            throw new IllegalStateException("Layout '${l.name}': ${why ?: 'Morpheus did not create it (HTTP ' + r.status + ')'}")
        }
        created << ['/api/library/cluster-layouts', made.id]
        Map<String, String> roleCodes = ((src.computeServers ?: []) as List<Map>).findAll { it.computeServerType?.code }
            .collectEntries { [(it.nodeType as String): it.computeServerType.code as String] }
        if (afterClone && roleCodes) {
            List<Map> before = ((api.get("/api/library/cluster-layouts/${made.id}").layout?.computeServers ?: []) as List<Map>)
            List<Map> fix = before.findAll { roleCodes[it.nodeType as String] && it.computeServerType?.code != roleCodes[it.nodeType as String] }
                .collect { [id: it.id, nodeTypeId: it.containerType?.id, serverType: roleCodes[it.nodeType as String]] }
            if (fix) afterClone.call(fix)
            List<Map> after = ((api.get("/api/library/cluster-layouts/${made.id}").layout?.computeServers ?: []) as List<Map>)
            List wrong = after.findAll { it.computeServerType?.code != roleCodes[it.nodeType as String] }
            List changed = after.findAll { Map a -> Map b = before.find { it.id == a.id }
                b && (a.containerType?.id != b.containerType?.id || a.nodeCount != b.nodeCount || a.nameSuffix != b.nameSuffix || a.dynamicCount != b.dynamicCount) }
            if (wrong || changed) warnings << "Layout ${l.name}: check the server type of the ${(wrong + changed)*.nodeType.unique().join(', ')} nodes.".toString()
        }
        done << "Created layout '${l.name}' from ${l.basedOn}".toString()
        made.id as Long
    }

    private static List<Map> evars(Object list) {
        list ? ((List<Map>) list).collect { [name: it.name, value: it.value, masked: it.masked ?: false, export: it.export ?: false] } : null
    }

    private Step step(String kind, Object name) { steps.find { it.kind == kind && it.name == name } }

    /** Creates, overwrites or keeps one item and returns its id. */
    private Long write(SelfApi api, Step st, String path, String key, Map body, List<List> created, List<String> done) {
        if (st.action == 'reuse') return st.existingId
        Map clean = body.findAll { k, v -> v != null }
        Map r = st.action == 'update' ? api.put("${path}/${st.existingId}", [(key): clean]) : api.post(path, [(key): clean])
        if (r._status >= 400 || r.success == false) {
            throw new IllegalStateException("${st.kind} '${st.name}': ${r.msg ?: r.errors ?: r.raw ?: ('HTTP ' + r._status)}")
        }
        Long id = (r[key]?.id ?: r.id ?: st.existingId) as Long
        if (st.action == 'create') created << [path, id]
        done << "${st.action == 'update' ? 'Updated' : 'Created'} ${st.kind.toLowerCase()} '${st.name}'".toString()
        id
    }
}
