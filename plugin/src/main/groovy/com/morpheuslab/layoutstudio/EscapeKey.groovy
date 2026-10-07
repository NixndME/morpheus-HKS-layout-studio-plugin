package com.morpheuslab.layoutstudio

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.GlobalUIComponentProvider
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel

/** Puts the small Escape key script on Morpheus pages; the integration page drops scripts inside plugin HTML. */
class EscapeKey implements GlobalUIComponentProvider {
    Plugin plugin
    MorpheusContext morpheus

    EscapeKey(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-layout-studio-keys' }
    String getName() { 'HKS Layout Studio keys' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }
    Renderer<?> getRenderer() { plugin.renderer }

    Boolean show(User user, Account account) { Access.canExport(user) }

    HTMLResponse renderTemplate(User user, Account account) {
        ViewModel<Map> m = new ViewModel<>()
        m.object = [:]
        plugin.renderer.renderTemplate('hbs/escape-key', m)
    }
}
