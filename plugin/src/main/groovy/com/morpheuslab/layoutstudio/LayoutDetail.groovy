package com.morpheuslab.layoutstudio

/** One layout as a flow: inputs, node lanes, extra steps, add-ons. Ready for the template. */
class LayoutDetail {
    Long id
    String name, description, kube, os, provision
    boolean own
    List<Map> inputs = []
    List<Map> envs = []
    List<Map> formGroups = []
    List<Map> lanes = []
    Map workflow           // extra steps for every node, or null
    List<Map> addons = []
    List<Map> otherAddons = []
    Map addonAdder, detailsPanel, deletePanel
    List<Map> cards = []   // every clickable card, for the detail panels

    /** Plain help for the fields Morpheus asks when a cluster is created: field name to [group, help]. */
    static final Map<String, List<String>> FORM_HELP = [
        sshMasterHosts : ['Machines', 'IP address of each master machine'],
        sshWorkerHosts : ['Machines', 'IP address of each worker machine'],
        clusterHostname: ['Machines', 'Name or address the cluster answers on'],
        loadBalancerId : ['Machines', 'Load balancer in front of the masters'],
        sshUsername    : ['Login', 'User on the machines that can use sudo'],
        sshPassword    : ['Login', 'Password of that user'],
        provisionKey   : ['Login', 'SSH key, used instead of a password'],
        sshPort        : ['Login', 'SSH port, usually 22'],
        dataDevice     : ['Storage', 'Empty disk for container data, like /dev/sdb'],
        lvmEnabled     : ['Storage', 'Use LVM on that disk'],
        softwareRaid   : ['Storage', 'Join several empty disks into one'],
        name           : ['Network', 'Network card name, like eth0 or enp1s0'],
        podCidr        : ['Network', 'Private address range for pods'],
        serviceCidr    : ['Network', 'Private address range for services']]
    static final List<String> FORM_GROUPS = ['Machines', 'Login', 'Storage', 'Network', 'Your own inputs', 'Settings']

    static final List<List<String>> STAGES = [['preProvision', 'Before provisioning'], ['provision', 'During provisioning'], ['postProvision', 'After provisioning']]

    static LayoutDetail build(SelfApi api, Long id) {
        Map l = api.get("/api/library/cluster-layouts/${id}").layout as Map
        if (!l) return null
        LayoutDetail d = new LayoutDetail(id: id, name: l.name, description: l.description, kube: l.clusterVersion ?: '-',
            os: l.computeVersion ?: '-', provision: l.provisionType?.name, own: l.account != null)
        d.inputs = (l.optionTypes ?: []).collect { Map o ->
            List<String> h = FORM_HELP[o.fieldName as String]
            [label: o.fieldLabel ?: o.name, required: o.required, group: h ? h[0] : 'Your own inputs',
             help: h ? h[1] : (o.description ?: o.helpBlock ?: o.placeHolder ?: "Asked as ${o.type ?: 'text'}")]
        }
        d.envs = (l.environmentVariables ?: []).collect { Map e -> [name: e.name ?: e.evarName, value: e.masked ? '(hidden)' : (e.defaultValue ?: e.value)] }
        d.formGroups = FORM_GROUPS.collect { String g ->
            List items = g == 'Settings'
                ? d.envs.collect { [label: it.name, help: "A setting for the scripts, set to ${it.value}"] }
                : d.inputs.findAll { it.group == g }
            [title: g, items: items]
        }.findAll { it.items }
        d.formGroups.each { Map g ->
            g.color = "hksl-g-${FORM_GROUPS.indexOf(g.title)}".toString()
            g.countLabel = g.items.size() == 1 ? '1 field' : "${g.items.size()} fields".toString()
        }
        boolean anyBuiltinNodeType = false
        ((l.computeServers ?: []) as List<Map>).sort { it.priorityOrder ?: 0 }.each { Map cs ->
            Map ct = api.get("/api/library/container-types/${cs.containerType?.id}").containerType as Map ?: [:]
            boolean ntOwn = ct.account != null
            if (!ntOwn) anyBuiltinNodeType = true
            List<Map> steps = []
            (ct.containerScripts ?: []).each { Map ref ->
                Map s = api.get("/api/library/container-scripts/${ref.id}").containerScript as Map
                if (s) steps << d.card('script', s.name, s.scriptPhase, !s.account, [
                    ['Type', 'Script (' + (s.scriptType ?: 'bash') + ')'], ['Stage', stageName(s.scriptPhase)],
                    ['Run as', s.sudoUser ? 'root (sudo)' : (s.runAsUser ?: 'default user')], ['Stop on error', s.failOnError ? 'Yes' : 'No']],
                    s.account ? s.script : null, [itemId: s.id, nodeTypeId: ct.id, editable: d.own && s.account != null, removable: d.own && ntOwn])
            }
            (ct.containerTemplates ?: []).each { Map ref ->
                Map t = api.get("/api/library/container-templates/${ref.id}").containerTemplate as Map
                if (t) steps << d.card('file', t.name, t.templatePhase, !t.account, [
                    ['Type', 'File template'], ['Stage', stageName(t.templatePhase)],
                    ['Written to', "${t.filePath ?: ''}/${t.fileName ?: ''}".replaceAll('//', '/')], ['Runs after writing', t.autoRun ? 'Yes' : 'No']],
                    t.account ? t.template : null, [itemId: t.id, nodeTypeId: ct.id, editable: d.own && t.account != null, removable: d.own && ntOwn])
            }
            String title = cs.nodeType == 'master' ? 'Master' : 'Worker'
            d.lanes << [role: cs.nodeType, title: title, count: cs.nodeCount, step: d.lanes.size() + 1,
                        nodeType: ct.name ?: cs.containerType?.name, nodeTypeId: ct.id, nodeTypeOwn: ntOwn, canAdd: d.own && ntOwn,
                        stages: STAGES.collect { st -> [title: st[1], phase: st[0], steps: steps.findAll { (it.phase ?: 'provision') == st[0] },
                            adder: d.own && ntOwn ? d.adder('addStep', "Add a step to ${title.toLowerCase()} nodes, ${st[1].toLowerCase()}", [target: 'node', nodeTypeId: ct.id, phase: st[0]]) : null] }]
        }
        Long wf = (l.taskSets ?: [])[0]?.id as Long
        if (wf || (d.own && anyBuiltinNodeType)) {
            List<Map> steps = []
            if (wf) {
                Map ts = api.get("/api/task-sets/${wf}").taskSet as Map
                ((ts?.taskSetTasks ?: []) as List<Map>).each { Map tt ->
                    Map task = api.get("/api/tasks/${tt.task?.id}").task as Map
                    if (task) steps << d.card('step', task.name, tt.taskPhase, false, [
                        ['Type', 'Workflow step (shell)'], ['Stage', stageName(tt.taskPhase)], ['Runs on', 'Every node of the cluster'],
                        ['Workflow', ts.name]], task.file?.content, [itemId: task.id, editable: d.own, removable: d.own, workflow: true])
                }
            }
            d.workflow = [id: wf, step: d.lanes.size() + 1, canAdd: d.own,
                          stages: STAGES.collect { st -> [title: st[1], phase: st[0], steps: steps.findAll { it.phase == st[0] },
                              adder: d.own ? d.adder('addStep', "Add a step for every node, ${st[1].toLowerCase()}", [target: 'workflow', phase: st[0], workflow: true]) : null] }]
        }
        String form = api.html("/library/cluster-layouts/${id}/clone")
        List<Long> pkgIds = (form =~ /name="package\.id" value="(\d+)"/).collect { it[1] as Long }
        pkgIds.each { Long pid ->
            Map p = api.get("/api/library/cluster-packages/${pid}").clusterPackage as Map
            if (!p) return
            String yaml = p.account ? (p.specTemplates ?: []).collect { Map s -> (api.get("/api/library/spec-templates/${s.id}").specTemplate?.file?.content ?: '') }.join('\n---\n') : null
            List<String> imgs = yaml ? AddonMaker.images(yaml) : []
            d.addons << d.card('addon', p.name, 'addon', !p.account, [
                ['Type', "Add-on (${p.type ?: p.packageType})"], ['Version', p.packageVersion], ['Installed', 'After all nodes are ready'],
                ['From', p.account ? p.description?.replaceAll(/\s*Images:.*$/, '') : 'Built into Morpheus'],
                ['Images', imgs.join(', ')], ['YAML parts', (p.specTemplates ?: [])*.name.join(', ')]], yaml, [itemId: pid, removable: d.own, kindAddon: true])
        }
        d.addonAdder = d.own ? d.adder('addAddon', 'Add an add-on', [:]) : null
        d.detailsPanel = d.own ? d.adder('editDetails', 'Edit layout', [counts: d.lanes.collect { [role: it.role, title: it.title, count: it.count] }])
                                : d.adder('copyLayout', 'Make an editable copy', [copyName: "${d.name} copy".toString()])
        if (d.own) {
            List<String> using = new LayoutRemover(api: api).clustersUsing(id)
            d.deletePanel = d.adder('deleteLayout', 'Delete layout', [usedBy: using.join(', '), inUse: !using.isEmpty()])
            d.otherAddons = Catalog.list(api, '/api/library/cluster-packages', 'clusterPackages')
                .findAll { it.enabled != false && !((it.id as Long) in pkgIds) }.sort { it.name }.collect { [id: it.id, name: it.name] }
        }
        d
    }

    /** A panel that holds a form, opened like a card. */
    private Map adder(String form, String title, Map extra) {
        Map a = [key: "c${cards.size()}", kind: 'form', name: title, (form): true, isForm: true] + extra
        cards << a
        a
    }

    private Map card(String kind, Object name, Object phase, boolean builtin, List<List> facts, Object content, Map extra) {
        Map c = [key: "c${cards.size()}", kind: kind, kindLabel: kind == 'addon' ? (builtin ? '' : 'added') : [script: 'Script', file: 'File', step: 'Step'][kind],
                 name: name, phase: phase, builtin: builtin, facts: facts.findAll { it[1] }.collect { [k: it[0], v: it[1]] },
                 content: content, hidden: builtin && kind in ['script', 'file']] + extra
        if (c.removable) {
            c.xkey = "x${cards.size()}"
            c.confirm = kind == 'addon' ? "Remove ${name} from this layout? The add-on stays in Morpheus."
                : extra.workflow ? "Delete the step ${name}? Every layout that uses this workflow loses it."
                : "Take ${name} out of this node type? The ${kind} stays in the library."
        }
        cards << c
        c
    }

    static String stageName(Object phase) { STAGES.find { it[0] == phase }?.get(1) ?: (phase ?: 'During provisioning') }

    int getAddonStep() { lanes.size() + (workflow ? 2 : 1) }
    int getInputCount() { inputs.size() }
    boolean getHasWorkflow() { workflow != null }
}
