import com.morpheuslab.layoutstudio.AddonMaker
import com.morpheuslab.layoutstudio.UiForm
import spock.lang.Specification

class UiFormSpec extends Specification {

    static final String HTML = '''
<form action="/other"><input name="x" value="1"></form>
<form action="/library/cluster-layouts/353" method="post">
  <input type="hidden" name="_method" value="PUT" id="_method" />
  <input type="text" name="computeTypeLayout.name" value="Lab &amp; Test">
  <input type="hidden" name="computeTypeLayout._creatable" /><input type="checkbox" name="computeTypeLayout.creatable" checked="checked" />
  <input type="checkbox" name="computeTypeLayout.hasAutoScale" />
  <select name="config.memorySizeType"><option value="gb">GB</option><option value="mb" selected>MB</option></select>
  <textarea name="notes">a &lt;b&gt;</textarea>
  <input type="hidden" name="computeTypeSets.nodeType" value="master"><input type="number" name="computeTypeSets.nodeCount" value="1">
  <input type="hidden" name="computeTypeSets.nodeType" value="worker"><input type="number" name="computeTypeSets.nodeCount" value="3">
  <input type="hidden" name="package.id" value="9"><input type="hidden" name="package.id" value="12">
  <button type="submit" name="submit">Save</button>
</form>'''

    def 'reads a form like a browser sends it'() {
        when:
        UiForm f = UiForm.parse(HTML, '/library/cluster-layouts/353')

        then:
        f.action == '/library/cluster-layouts/353'
        f.get('_method') == 'PUT'
        f.get('computeTypeLayout.name') == 'Lab & Test'
        f.get('computeTypeLayout.creatable') == 'on'
        f.get('computeTypeLayout.hasAutoScale') == null
        f.get('config.memorySizeType') == 'mb'
        f.get('notes') == 'a <b>'
        f.all('package.id') == ['9', '12']
        f.get('submit') == null
    }

    def 'changes repeated fields by position'() {
        when:
        UiForm f = UiForm.parse(HTML, '/library/cluster-layouts/353').setNth('computeTypeSets.nodeCount', 1, 2).removeAll('package.id').add('package.id', 51)

        then:
        f.all('computeTypeSets.nodeCount') == ['1', '2']
        f.all('package.id') == ['51']
    }

    def 'finds the images an add-on needs'() {
        expect:
        AddonMaker.images('spec:\n  containers:\n  - name: a\n    image: httpd:2.4\n  - image: "quay.io/x/y:1" # pinned\n  - image: httpd:2.4\n') == ['httpd:2.4', 'quay.io/x/y:1']
    }
}

class HelmSpec extends Specification {
    def 'rendered chart resources get the namespace, cluster-wide ones do not'() {
        given:
        String yaml = 'apiVersion: v1\nkind: Service\nmetadata:\n  name: web\n---\nkind: ClusterRole\nmetadata:\n  name: r\n---\nkind: ConfigMap\nmetadata:\n  name: c\n  namespace: other\n'

        when:
        String out = com.morpheuslab.layoutstudio.Helm.withNamespace(yaml, 'demo')

        then:
        out =~ /(?s)kind: Service.*namespace: demo/
        !(out =~ /(?s)kind: ClusterRole\s+metadata:\s+name: r\s+namespace/)
        out.contains('namespace: other')
    }
}
