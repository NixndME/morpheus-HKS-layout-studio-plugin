package com.morpheuslab.layoutstudio

/** Adds, changes and removes steps: scripts and files in your own node types, and workflow steps for every node. */
class Steps {
    static final List<String> PHASES = ['preProvision', 'provision', 'postProvision']

    SelfApi api
    LayoutEditor editor

    /** Adds a script or file to your own node type. Returns an error or null. */
    String addToNodeType(Long nodeTypeId, String kind, String phase, String name, String content, String fileName, String filePath) {
        Map nt = api.get("/api/library/container-types/${nodeTypeId}").containerType as Map
        if (!nt?.account) return 'Built-in node types can not be changed. Use a workflow step instead.'
        if (!(phase in PHASES)) return 'Choose a stage.'
        if (!name?.trim()) return 'Give the step a name.'
        if (kind == 'file') {
            if (!fileName?.trim() || !filePath?.trim()) return 'A file needs a file name and a folder.'
            Map r = api.post('/api/library/container-templates', [containerTemplate: [name: name.trim(), fileName: fileName.trim(), filePath: filePath.trim(),
                templatePhase: phase, template: content ?: '', autoRun: false]])
            Long id = r.containerTemplate?.id as Long
            if (!id) return "Morpheus did not save the file: ${r.msg ?: r.errors}".toString()
            r = api.put("/api/library/container-types/${nodeTypeId}", [containerType: [templates: (nt.containerTemplates*.id ?: []) + [id]]])
            return r.success == false ? "Morpheus did not add the file: ${r.msg}".toString() : null
        }
        Map r = api.post('/api/library/container-scripts', [containerScript: [name: name.trim(), scriptType: 'bash', scriptPhase: phase,
            script: content ?: '', sudoUser: true, failOnError: true]])
        Long id = r.containerScript?.id as Long
        if (!id) return "Morpheus did not save the script: ${r.msg ?: r.errors}".toString()
        r = api.put("/api/library/container-types/${nodeTypeId}", [containerType: [scripts: (nt.containerScripts*.id ?: []) + [id]]])
        r.success == false ? "Morpheus did not add the script: ${r.msg}".toString() : null
    }

    /** Takes a script or file out of your own node type. The script itself stays in the library. */
    String removeFromNodeType(Long nodeTypeId, String kind, Long itemId) {
        Map nt = api.get("/api/library/container-types/${nodeTypeId}").containerType as Map
        if (!nt?.account) return 'Built-in node types can not be changed.'
        Map body = kind == 'file' ? [templates: (nt.containerTemplates*.id ?: []) - [itemId]] : [scripts: (nt.containerScripts*.id ?: []) - [itemId]]
        Map r = api.put("/api/library/container-types/${nodeTypeId}", [containerType: body])
        r.success == false ? "Morpheus did not remove it: ${r.msg}".toString() : null
    }

    /** Saves new text for your own script or file. */
    String updateContent(String kind, Long itemId, String content) {
        Map r = kind == 'file' ? api.put("/api/library/container-templates/${itemId}", [containerTemplate: [template: content ?: '']])
                               : api.put("/api/library/container-scripts/${itemId}", [containerScript: [script: content ?: '']])
        r.success == false ? "Morpheus did not save it: ${r.msg ?: r.errors}".toString() : null
    }

    /** Adds a shell step that runs on every node of the layout, through the layout's workflow. */
    String addWorkflowStep(Long layoutId, String phase, String name, String content) {
        if (!(phase in PHASES)) return 'Choose a stage.'
        if (!name?.trim()) return 'Give the step a name.'
        Map t = api.post('/api/tasks', [task: [name: uniqueTaskName(api, name.trim()), taskType: [code: 'script'], executeTarget: 'resource',
            file: [sourceType: 'local', content: content ?: ''], taskOptions: ['shell.sudo': 'on']]])
        Long taskId = t.task?.id as Long
        if (!taskId) return "Morpheus did not save the step: ${t.msg ?: t.errors}".toString()
        Map l = api.get("/api/library/cluster-layouts/${layoutId}").layout as Map
        Long wf = (l?.taskSets ?: [])[0]?.id as Long
        if (wf) {
            List tasks = workflowTasks(wf) + [[taskId: taskId, taskPhase: phase]]
            Map r = api.put("/api/task-sets/${wf}", [taskSet: [tasks: tasks]])
            return r.success == false ? "Morpheus did not add the step: ${r.msg}".toString() : null
        }
        Map r = api.post('/api/task-sets', [taskSet: [name: "${l?.name} steps".toString(), type: 'provision', tasks: [[taskId: taskId, taskPhase: phase]]]])
        wf = r.taskSet?.id as Long
        if (!wf) return "Morpheus did not save the workflow: ${r.msg ?: r.errors}".toString()
        editor.setWorkflow(layoutId, wf)
    }

    String removeWorkflowStep(Long layoutId, Long taskId) {
        Map l = api.get("/api/library/cluster-layouts/${layoutId}").layout as Map
        Long wf = (l?.taskSets ?: [])[0]?.id as Long
        if (!wf) return 'This layout has no workflow.'
        Map r = api.put("/api/task-sets/${wf}", [taskSet: [tasks: workflowTasks(wf).findAll { it.taskId != taskId }]])
        if (r.success == false) return "Morpheus did not remove the step: ${r.msg}".toString()
        api.delete("/api/tasks/${taskId}")
        null
    }

    String updateWorkflowStep(Long taskId, String content) {
        Map r = api.put("/api/tasks/${taskId}", [task: [file: [sourceType: 'local', content: content ?: '']]])
        r.success == false ? "Morpheus did not save the step: ${r.msg}".toString() : null
    }

    /** Morpheus task names must be unique, so a taken name gets a number. */
    static String uniqueTaskName(SelfApi api, String name) {
        Set<String> taken = Catalog.list(api, '/api/tasks', 'tasks')*.name as Set
        if (!(name in taken)) return name
        int n = 2
        while ("${name} ${n}".toString() in taken) n++
        "${name} ${n}".toString()
    }

    private List<Map> workflowTasks(Long wf) {
        Map ts = api.get("/api/task-sets/${wf}").taskSet as Map
        ((ts?.taskSetTasks ?: []) as List<Map>).collect { [taskId: it.task?.id as Long, taskPhase: it.taskPhase] }
    }
}
