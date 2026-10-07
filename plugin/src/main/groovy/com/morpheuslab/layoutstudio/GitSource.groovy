package com.morpheuslab.layoutstudio

import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

import javax.net.ssl.HttpsURLConnection
import java.nio.file.Files

/** Gets an add-on once from Git or a file link; it is then kept in Morpheus. */
@Slf4j
class GitSource {

    /** Returns a chart, YAML files or an error, plus a temp folder to delete. */
    static Map fetch(String url, String ref, String path, String user, String token, boolean skipTls) {
        if (!(url ==~ /(?i)https?:\/\/.+/)) return [error: 'Use an http or https address.']
        File work = Files.createTempDirectory('hksl-git').toFile()
        try {
            if (url ==~ /(?i).+\.(ya?ml|tgz)(\?.*)?$/) return file(url, user, token, skipTls, work)
            def clone = Git.cloneRepository().setURI(url).setDirectory(new File(work, 'repo')).setDepth(1).setCloneAllBranches(false).setTimeout(60)
            if (ref) clone.setBranch(ref)
            if (token || user || skipTls) clone.setCredentialsProvider(new Answers(user ?: 'git', token ?: '', skipTls))
            clone.call().close()
            File base = new File(work, 'repo/' + (path ?: '').replaceAll(/^\/+|\/+$/, ''))
            if (!base.canonicalPath.startsWith(new File(work, 'repo').canonicalPath)) return [error: 'That folder is outside the repository.', work: work]
            if (!base.exists()) return [error: "Folder ${path} was not found in the repository.".toString(), work: work]
            if (new File(base, 'Chart.yaml').exists()) return [chartDir: base, work: work]
            Map<String, String> yaml = [:]
            base.eachFileRecurse { File f -> if (f.isFile() && f.name ==~ /(?i).+\.ya?ml/ && !f.path.contains('/.git/')) yaml[base.toPath().relativize(f.toPath()).toString()] = f.text }
            if (!yaml) return [error: 'No YAML files or Helm chart found there.', work: work]
            [yaml: yaml.sort(), work: work]
        } catch (Exception e) {
            log.warn("HKS Layout Studio: git fetch failed for ${url}: ${e}")
            [error: "Could not get it from ${url}: ${(e.cause ?: e).message?.take(200)}".toString(), work: work]
        }
    }

    private static Map file(String url, String user, String token, boolean skipTls, File work) {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        if (skipTls && c instanceof HttpsURLConnection) {
            c.SSLSocketFactory = SelfApi.TRUST_ALL.socketFactory
            c.hostnameVerifier = { h, s -> true }
        }
        c.connectTimeout = 15000; c.readTimeout = 60000
        if (token) c.setRequestProperty('Authorization', user ? 'Basic ' + "${user}:${token}".bytes.encodeBase64() : "Bearer ${token}")
        if (c.responseCode >= 400) return [error: "The link answered ${c.responseCode}.".toString(), work: work]
        byte[] data = c.inputStream.bytes
        String name = url.replaceAll(/\?.*$/, '').tokenize('/').last()
        if (name.toLowerCase().endsWith('.tgz')) {
            File f = new File(work, name); f.bytes = data
            return [chartFile: f, work: work]
        }
        [yaml: [(name): new String(data, 'UTF-8')], work: work]
    }
}

/** Gives JGit the login, and says yes to skipping the certificate check when asked to. */
class Answers extends CredentialsProvider {
    String user, secret
    boolean skipTls

    Answers(String user, String secret, boolean skipTls) { this.user = user; this.secret = secret; this.skipTls = skipTls }

    boolean isInteractive() { false }
    boolean supports(CredentialItem... items) { true }

    boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        items.each { CredentialItem i ->
            if (i instanceof CredentialItem.Username) i.value = user
            else if (i instanceof CredentialItem.Password) i.value = secret.toCharArray()
            else if (i instanceof CredentialItem.YesNoType) i.value = skipTls
        }
        true
    }
}
