import com.morpheuslab.layoutstudio.Bundle
import com.morpheuslab.layoutstudio.Catalog
import com.morpheuslab.layoutstudio.Importer
import spock.lang.Specification

class LayoutStudioSpec extends Specification {

    static Map sample() {
        [format: Bundle.FORMAT, version: 1, exportedBy: 'admin', morpheus: '9.0.2',
         layouts: [[name: 'My HKS', clusterType: 'kubernetes-cluster', provisionType: 'manual', computeVersion: '24.04',
                    optionTypes: ['builtin:computeTypeLayout.manual.sshMasterHosts', 'Team'],
                    nodes: [[role: 'master', count: 1, nodeType: 'My master'], [role: 'worker', count: 3, nodeType: 'builtin:kubernetes-ubuntu-24.04-worker-morpheus-amd64']]]],
         nodeTypes: [[name: 'My master', version: '24.04', provisionType: 'manual', scripts: ['setup'], templates: ['init.sh']]],
         scripts: [[name: 'setup', phase: 'provision', type: 'bash', content: '#!/bin/bash\necho one\necho two\n']],
         templates: [[name: 'init.sh', fileName: 'init.sh', filePath: '/opt', phase: 'provision', content: 'x=1']],
         optionTypes: [[name: 'Team', fieldName: 'team', fieldLabel: 'Team', type: 'text']]]
    }

    static Catalog target() {
        new Catalog(clusterTypes: ['kubernetes-cluster': 1L], provisionTypes: [manual: 6L],
            builtinNodeTypes: ['kubernetes-ubuntu-24.04-worker-morpheus-amd64': [id: 435]],
            builtinOptionTypes: ['computeTypeLayout.manual.sshMasterHosts': 1387L])
    }

    def 'YAML round trip keeps scripts readable and intact'() {
        when:
        String yaml = Bundle.toYaml(sample())
        Map back = Bundle.parse(yaml).bundle as Map

        then:
        yaml.contains('content: |')
        yaml.contains('  echo one')
        back.scripts[0].content == '#!/bin/bash\necho one\necho two\n'
        back.layouts[0].nodes[1].nodeType == 'builtin:kubernetes-ubuntu-24.04-worker-morpheus-amd64'
    }

    def 'JSON files are accepted too'() {
        expect:
        Bundle.parse(groovy.json.JsonOutput.toJson(sample())).bundle.layouts[0].name == 'My HKS'
    }

    def 'bad files are refused with a plain reason'() {
        expect:
        Bundle.parse(text).error.contains(reason)

        where:
        text                                                    | reason
        ''                                                      | 'empty'
        'just text'                                             | 'not a layout backup'
        'format: something-else\nversion: 1\nlayouts: [a]'      | 'format must be'
        "format: ${Bundle.FORMAT}\nversion: 99\nlayouts: [a]"   | 'newer version'
        "format: ${Bundle.FORMAT}\nversion: 1\nlayouts: []"     | 'no layouts'
        'a: [unclosed'                                          | 'not a valid YAML'
    }

    def 'fresh Morpheus: everything is created, built-ins are resolved'() {
        when:
        Importer im = new Importer(sample(), target(), false).plan()

        then:
        !im.blocked
        im.steps*.action == ['create'] * 5
        im.steps*.kind == ['Script', 'File template', 'Option type', 'Node type', 'Layout']
    }

    def 'existing items with the same content are kept; different ones only replaced on request'() {
        given:
        Catalog c = target()
        c.ownScripts = [setup: [id: 7, script: '#!/bin/bash\necho one\necho two', scriptPhase: 'provision']]   // Morpheus trims the newline
        c.ownTemplates = ['init.sh': [id: 8, template: 'x=2', fileName: 'init.sh', filePath: '/opt']]

        expect:
        new Importer(sample(), c, false).plan().steps.find { it.kind == 'Script' }.action == 'reuse'
        new Importer(sample(), c, false).plan().steps.find { it.kind == 'File template' }.action == 'reuse'
        new Importer(sample(), c, true).plan().steps.find { it.kind == 'File template' }.action == 'update'
    }

    def 'a new node type that needs a built-in script is blocked (the API cannot attach it)'() {
        given:
        Map b = sample()
        b.nodeTypes[0].scripts = ['builtin:kube-adm-master-setup-script-v1', 'setup']

        when:
        Importer im = new Importer(b, target(), false).plan()

        then:
        im.blocked
        im.steps.find { it.kind == 'Node type' }.detail.contains('kube-adm-master-setup-script-v1')
    }

    def 'a built-in node type missing on this Morpheus blocks the layout'() {
        given:
        Catalog c = target()
        c.builtinNodeTypes = [:]

        when:
        Importer.Step st = new Importer(sample(), c, false).plan().steps.find { it.kind == 'Layout' }

        then:
        st.blocked
        st.detail.contains('different Morpheus version')
    }

    def 'a Kubernetes layout without basedOn gets a clear warning'() {
        expect:
        new Importer(sample(), target(), false).plan().warnings.any { it.contains('has no basedOn') }
    }

    def 'basedOn must exist here; missing add-ons are skipped with a note'() {
        given:
        Map b = sample()
        b.layouts[0].basedOn = 'kubernetes-1.35-ubuntu-24.04-morpheus-amd64-single'
        b.layouts[0].packages = ['kubernetes-calico-3-31-1-package', 'not-here']
        Catalog c = target()

        when:
        Importer.Step missing = new Importer(b, c, false).plan().steps.find { it.kind == 'Layout' }
        c.builtinLayouts = ['kubernetes-1.35-ubuntu-24.04-morpheus-amd64-single': [id: 162]]
        c.packages = ['kubernetes-calico-3-31-1-package': 9L]
        Importer ok = new Importer(b, c, false).plan()

        then:
        missing.blocked
        missing.detail.contains('different Morpheus version')
        !ok.blocked
        !ok.warnings.any { it.contains('has no basedOn') }
        ok.warnings.any { it.contains('add-on not-here not found') }
    }

    def 'own add-ons and workflows in the file are made again, existing ones kept'() {
        given:
        Map b = sample()
        b.layouts[0].basedOn = 'kubernetes-1.35-ubuntu-24.04-morpheus-amd64-single'
        b.layouts[0].packages = ['ls-apache-2-4', 'kubernetes-calico-3-31-1-package']
        b.layouts[0].workflows = ['Lab steps']
        b.addons = [[code: 'ls-apache-2-4', name: 'apache 2.4', yaml: [[name: 'apache 2.4', content: 'kind: Namespace']]]]
        b.workflows = [[name: 'Lab steps', steps: [[name: 'marker', phase: 'postProvision', type: 'script', content: 'echo hi']]]]
        Catalog c = target()
        c.builtinLayouts = ['kubernetes-1.35-ubuntu-24.04-morpheus-amd64-single': [id: 162]]
        c.packages = ['kubernetes-calico-3-31-1-package': 9L]

        when:
        Importer fresh = new Importer(b, c, false).plan()
        c.packages['ls-apache-2-4'] = 51L
        c.workflows['Lab steps'] = 7L
        Importer again = new Importer(b, c, false).plan()

        then:
        fresh.steps.find { it.kind == 'Add-on' }.action == 'create'
        fresh.steps.find { it.kind == 'Workflow' }.action == 'create'
        !fresh.warnings.any { it.contains('not found') }
        again.steps.findAll { it.kind in ['Add-on', 'Workflow'] }*.action == ['reuse', 'reuse']
    }

    def 'a zip with one file per layout is read as one restore, shared items once'() {
        given:
        Map first = sample()
        Map b = sample()
        b.layouts[0].name = 'Other HKS'
        ByteArrayOutputStream buf = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(buf).withCloseable { z ->
            [["my-hks.yaml", first], ["other-hks.yaml", b]].each { e -> z.putNextEntry(new java.util.zip.ZipEntry(e[0])); z.write(Bundle.toYaml(e[1]).bytes); z.closeEntry() }
            z.putNextEntry(new java.util.zip.ZipEntry('readme.txt')); z.write('x'.bytes); z.closeEntry()
        }

        when:
        Map r = Bundle.read(buf.toByteArray())

        then:
        r.bundle.layouts*.name == ['My HKS', 'Other HKS']
        r.bundle.scripts.size() == 1
        r.bundle.nodeTypes.size() == 1
    }

    def 'a bad file inside the zip stops the restore'() {
        given:
        ByteArrayOutputStream buf = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(buf).withCloseable { z -> z.putNextEntry(new java.util.zip.ZipEntry('bad.yaml')); z.write('format: nope'.bytes); z.closeEntry() }

        expect:
        Bundle.read(buf.toByteArray()).error.startsWith('bad.yaml:')
    }
}
