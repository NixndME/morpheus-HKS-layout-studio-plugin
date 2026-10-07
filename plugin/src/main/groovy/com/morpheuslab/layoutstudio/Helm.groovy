package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Turns a Helm chart into plain Kubernetes YAML with the helm program packed in this plugin. No network. */
@Slf4j
class Helm {
    static final String RESOURCE = '/helm/linux-amd64/helm'

    /** Returns [yaml, chart, version, warnings] or [error]. chart is a .tgz file or a chart folder. */
    static Map render(File chart, String release, String namespace, String values, String kubeVersion) {
        File bin = binary()
        if (!bin) return [error: 'Helm is not available in this plugin build.']
        Path work = Files.createTempDirectory('hksl-helm')
        try {
            List<String> cmd = [bin.path, 'template', release, chart.path, '--namespace', namespace, '--include-crds', '--skip-tests']
            if (kubeVersion) cmd += ['--kube-version', kubeVersion]
            if (values?.trim()) {
                File vf = work.resolve('values.yaml').toFile()
                vf.text = values
                cmd += ['-f', vf.path]
            }
            Map show = run([bin.path, 'show', 'chart', chart.path], work)
            Map r = run(cmd, work)
            if (r.code != 0) return [error: "Helm could not render the chart: ${(r.err ?: r.out).toString().trim().take(400)}".toString()]
            Map meta = [:]
            (show.out as String).eachLine { String line -> def m = line =~ /^(name|version|appVersion):\s*(.+)$/; if (m) meta[m[0][1]] = m[0][2].trim().replaceAll(/^["']|["']$/, '') }
            List<String> warnings = []
            if (chartUsesLookup(chart)) warnings << 'This chart reads the live cluster (lookup). Those parts are empty in the saved YAML.'
            [yaml: withNamespace(r.out as String, namespace), chart: meta.name, version: meta.version, appVersion: meta.appVersion, warnings: warnings]
        } finally {
            work.toFile().deleteDir()
        }
    }

    static final Set<String> CLUSTER_WIDE = ['Namespace', 'CustomResourceDefinition', 'ClusterRole', 'ClusterRoleBinding', 'StorageClass',
        'PersistentVolume', 'PriorityClass', 'IngressClass', 'RuntimeClass', 'CSIDriver', 'APIService', 'ValidatingWebhookConfiguration',
        'MutatingWebhookConfiguration', 'ValidatingAdmissionPolicy', 'ValidatingAdmissionPolicyBinding'] as Set

    /** helm install adds the namespace itself; plain YAML needs it written into each namespaced resource. */
    static String withNamespace(String yaml, String namespace) {
        org.yaml.snakeyaml.DumperOptions o = new org.yaml.snakeyaml.DumperOptions()
        o.defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
        o.width = 200
        org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()), new org.yaml.snakeyaml.representer.Representer(o), o)
        List docs = []
        y.loadAll(yaml).each { Object d ->
            if (!(d instanceof Map)) return
            Map m = (Map) d
            if (m.kind && !(m.kind in CLUSTER_WIDE) && m.metadata instanceof Map && !((Map) m.metadata).namespace) ((Map) m.metadata).namespace = namespace
            docs << m
        }
        docs.collect { y.dump(it).trim() }.join('\n---\n') + '\n'
    }

    /** Chart name and version from Chart.yaml. Returns [name, version] or [error]. */
    static Map info(File chart) {
        File bin = binary()
        if (!bin) return [error: 'Helm is not available in this plugin build.']
        Path work = Files.createTempDirectory('hksl-helm')
        try {
            Map r = run([bin.path, 'show', 'chart', chart.path], work)
            if (r.code != 0) return [error: "This is not a Helm chart: ${(r.err ?: '').toString().trim().take(300)}".toString()]
            Map meta = [:]
            (r.out as String).eachLine { String line -> def m = line =~ /^(name|version|appVersion):\s*(.+)$/; if (m) meta[m[0][1]] = m[0][2].trim().replaceAll(/^["']|["']$/, '') }
            meta.name ? meta : [error: 'The chart has no name.']
        } finally {
            work.toFile().deleteDir()
        }
    }

    private static Map run(List<String> cmd, Path home) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
        // keep helm away from any user config, cache or network repos
        pb.environment().putAll([HELM_CACHE_HOME: home.resolve('cache').toString(), HELM_CONFIG_HOME: home.resolve('config').toString(),
                                 HELM_DATA_HOME: home.resolve('data').toString(), HOME: home.toString()])
        Process p = pb.start()
        StringBuilder out = new StringBuilder(), err = new StringBuilder()
        Thread t1 = Thread.start { out << p.inputStream.getText('UTF-8') }
        Thread t2 = Thread.start { err << p.errorStream.getText('UTF-8') }
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return [code: -1, out: '', err: 'Helm took longer than 2 minutes.'] }
        t1.join(); t2.join()
        [code: p.exitValue(), out: out.toString(), err: err.toString()]
    }

    /** Unpacks the helm program once per plugin version. */
    private static synchronized File binary() {
        InputStream src = Helm.getResourceAsStream(RESOURCE)
        if (!src) return null
        String ver = Helm.getResourceAsStream('/helm/VERSION')?.getText('UTF-8')?.trim() ?: 'x'
        File dir = new File(System.getProperty('java.io.tmpdir'), "hks-layout-studio/helm-${ver}")
        File bin = new File(dir, 'helm')
        if (!bin.canExecute()) {
            dir.mkdirs()
            File part = new File(dir, 'helm.part')
            part.withOutputStream { it << src }
            part.setExecutable(true, true)
            part.renameTo(bin)
            log.info("HKS Layout Studio: helm ${ver} unpacked to ${bin}")
        }
        src.close()
        bin
    }

    /** True when a chart template calls lookup, which needs a live cluster. */
    static boolean chartUsesLookup(File chart) {
        try {
            Map<String, String> files = chart.isDirectory()
                ? chart.listFiles() ? walk(chart) : [:]
                : TarGz.textFiles(chart.bytes)
            files.any { name, text -> (name.endsWith('.tpl') || name.endsWith('.yaml')) && name.contains('templates/') && text =~ /\blookup\s+"/ }
        } catch (Throwable ignored) {
            false
        }
    }

    private static Map<String, String> walk(File dir) {
        Map<String, String> out = [:]
        dir.eachFileRecurse { File f -> if (f.isFile() && f.length() < 1048576) out[f.path] = f.text }
        out
    }
}

/** Reads the text files inside a .tgz without extra libraries. */
class TarGz {
    static Map<String, String> textFiles(byte[] tgz) {
        Map<String, String> out = [:]
        DataInputStream in = new DataInputStream(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(tgz)))
        byte[] header = new byte[512]
        while (true) {
            try { in.readFully(header) } catch (EOFException ignored) { break }
            String name = new String(header, 0, 100, 'UTF-8').replaceAll(/\u0000.*$/, '')
            if (!name) break
            long size = Long.parseLong(new String(header, 124, 12, 'UTF-8').replaceAll(/[\u0000 ]/, '') ?: '0', 8)
            byte[] body = new byte[(int) size]
            in.readFully(body)
            long pad = (512 - size % 512) % 512
            in.skipBytes((int) pad)
            if (header[156] == ('0' as char) || header[156] == 0) out[name] = new String(body, 'UTF-8')
        }
        out
    }
}
