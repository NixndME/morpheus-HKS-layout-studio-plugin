package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

/** Saves an add-on in Morpheus: one Kubernetes spec template with the YAML, and a cluster package around it. */
@Slf4j
class AddonMaker {

    /** Returns [packageId, name, images] or [error]. */
    static Map create(SelfApi api, String name, String version, String yaml, String source) {
        name = name?.trim(); version = version?.trim() ?: '1.0'
        if (!name) return [error: 'Give the add-on a name.']
        if (!yaml?.trim()) return [error: 'The add-on has no YAML.']
        String code = "ls-${LayoutsController.slug(name)}-${LayoutsController.slug(version)}"
        if (Catalog.list(api, '/api/library/cluster-packages', 'clusterPackages').any { it.code == code }) {
            return [error: "An add-on called ${name} ${version} already exists. Use another name or version.".toString()]
        }
        List<String> imgs = images(yaml)
        Long spec = specTemplate(api, "${name} ${version}", yaml)
        if (!spec) return [error: 'Morpheus did not save the YAML.']
        Map r = api.post('/api/library/cluster-packages', [clusterPackage: [
            name: "${name} ${version}", code: code, enabled: true, type: 'apps', packageType: LayoutsController.slug(name),
            packageVersion: version, specTemplates: [spec],
            description: ("${source}." + (imgs ? " Images: ${imgs.join(', ')}" : '')).take(1000)]])
        Long pkg = (r.clusterPackage?.id ?: r.id) as Long
        if (r._status >= 400 || !pkg) {
            api.delete("/api/library/spec-templates/${spec}")
            return [error: "Morpheus did not save the add-on: ${r.msg ?: r.errors ?: r._status}".toString()]
        }
        log.info("HKS Layout Studio: add-on ${code} created, package ${pkg}, spec ${spec}")
        [packageId: pkg, name: "${name} ${version}".toString(), images: imgs]
    }

    /** Makes an add-on from a backup file with its original code. Returns [packageId, specId] or [error]. */
    static Map restore(SelfApi api, Map a) {
        String yaml = ((a.yaml ?: []) as List<Map>).collect { it.content ?: '' }.join('\n---\n')
        Long spec = specTemplate(api, (a.name ?: a.code) as String, yaml)
        if (!spec) return [error: "Morpheus did not save the YAML of ${a.name}.".toString()]
        Map r = api.post('/api/library/cluster-packages', [clusterPackage: [name: a.name, code: a.code, enabled: true, type: a.type ?: 'apps',
            packageType: a.packageType ?: LayoutsController.slug(a.name as String), packageVersion: a.version ?: '1.0', specTemplates: [spec],
            description: a.description]])
        Long pkg = (r.clusterPackage?.id ?: r.id) as Long
        if (r._status >= 400 || !pkg) {
            api.delete("/api/library/spec-templates/${spec}")
            return [error: "Morpheus did not save the add-on ${a.name}: ${r.msg ?: r.errors ?: r._status}".toString()]
        }
        [packageId: pkg, specId: spec]
    }

    /** New YAML for a spec template, through Morpheus' edit form (the REST API refuses it). Returns an error or null. */
    static String updateYaml(SelfApi api, Long specId, String yaml) {
        UiForm f = UiForm.parse(api.html("/library/resource-specs/${specId}/edit"), "/library/resource-specs/${specId}")
        if (!f) return 'Could not open the add-on YAML.'
        f.set('fileContent.sourceType', 'local').set('fileContent.content', yaml ?: '').set('hidden.fileContent.content', yaml ?: '')
        api.form(f.action, f.fields)
        String now = api.get("/api/library/spec-templates/${specId}").specTemplate?.file?.content
        (now ?: '').trim() == (yaml ?: '').trim() ? null : 'Morpheus did not save the new YAML.'
    }

    /** Container images named in the YAML, so they can be put in a local registry first. */
    static List<String> images(String yaml) {
        (yaml =~ /(?m)^\s*-?\s*image:\s*["']?([^"'\s#]+)/).collect { it[1] as String }.unique().sort()
    }

    /** Joins several YAML files into one, in name order. */
    static String join(Map<String, String> files) {
        files.collect { n, t -> "# Source: ${n}\n${t.trim()}" }.join('\n---\n') + '\n'
    }

    /** The spec template is created with Morpheus' own form; the REST API refuses it on 9.0.2. */
    private static Long specTemplate(SelfApi api, String name, String yaml) {
        String html = api.html('/library/resource-specs/create')
        UiForm f = UiForm.parse(html, '/library/resource-specs')
        if (!f) return null
        def kube = html =~ /(?i)<option[^>]*value="(\d+)"[^>]*>\s*Kubernetes Spec\s*</
        f.set('template.name', name).set('template.type.id', kube ? kube[0][1] : '1')
         .set('fileContent.sourceType', 'local').set('fileContent.content', yaml).set('hidden.fileContent.content', yaml)
        api.form(f.action, f.fields)
        Catalog.list(api, '/api/library/spec-templates', 'specTemplates').findAll { it.name == name }.max { it.id as Long }?.id as Long
    }
}
