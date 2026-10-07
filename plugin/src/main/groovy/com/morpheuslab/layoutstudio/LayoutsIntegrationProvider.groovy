package com.morpheuslab.layoutstudio

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractGenericIntegrationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

/** Adds HKS Layout Studio under Administration > Integrations. */
class LayoutsIntegrationProvider extends AbstractGenericIntegrationProvider {

    Plugin plugin
    MorpheusContext morpheus

    LayoutsIntegrationProvider(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-layout-studio-integration' }
    String getName() { 'HKS Layout Studio' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    String getCategory() { 'other' }
    List<OptionType> getOptionTypes() { [] }
    void refresh(AccountIntegration integration) { }
    Icon getIcon() { new Icon(path: 'hks-layout-studio.svg', darkPath: 'hks-layout-studio-dark.svg') }

    HTMLResponse renderTemplate(AccountIntegration integration) {
        ViewModel<PageView> m = new ViewModel<>()
        m.object = PageView.build(integration?.id)
        plugin.renderer.renderTemplate('hbs/integration', m)
    }
}
