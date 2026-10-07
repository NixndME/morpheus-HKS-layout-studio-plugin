package com.morpheuslab.layoutstudio

import com.morpheusdata.model.User
import groovy.util.logging.Slf4j

/** Role permission: none, read (export) or full (export and restore). */
class Access {
    static String level(User user) { user?.permissions?.get(LayoutStudioPlugin.PERMISSION) as String ?: 'none' }
    static boolean canExport(User user) { level(user) in ['read', 'full'] }
    static boolean canImport(User user) { level(user) == 'full' }
}

/** The CSRF token for forms and API calls. */
@Slf4j
class Csrf {
    static Map token() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            Object req = rch.getMethod('getRequestAttributes').invoke(null)?.getRequest()
            Object tok = req?.getAttribute('_csrf') ?: req?.getAttribute('org.springframework.security.web.csrf.CsrfToken')
            if (tok) return [param: tok.parameterName as String, header: tok.headerName as String, value: tok.token as String]
        } catch (Throwable t) {
            log.warn("HKS Layout Studio: no CSRF token available: ${t}")
        }
        [param: '_csrf', header: 'X-CSRF-TOKEN', value: '']
    }
}
