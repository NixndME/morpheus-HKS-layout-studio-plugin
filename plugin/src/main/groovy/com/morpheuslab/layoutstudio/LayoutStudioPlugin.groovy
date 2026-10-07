package com.morpheuslab.layoutstudio

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer

/** HKS Layout Studio: back up and restore cluster layouts. Works offline. */
class LayoutStudioPlugin extends Plugin {

    static final String PERMISSION = 'hks-layout-studio'

    @Override
    String getCode() { 'hks-layout-studio' }

    @Override
    void initialize() {
        setName('HKS Layout Studio')
        setDescription('Back up and restore cluster layouts')
        // a plugin with routes needs its own renderer on Morpheus 9.0.2
        HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClassLoader())
        r.registerAssetHelper(getName())
        r.registerNonceHelper(morpheus.getWebRequest())
        r.registerI18nHelper(this, morpheus)
        setRenderer(r)
        Permission p = Permission.build('HKS Layout Studio', PERMISSION, [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        p.subCategory = 'HKS Layout Studio'
        setPermissions([p])
        registerProvider(new LayoutsIntegrationProvider(this, morpheus))
        registerProvider(new EscapeKey(this, morpheus))
        controllers.add(new LayoutsController(this, morpheus))
    }

    @Override
    void onDestroy() { }
}
