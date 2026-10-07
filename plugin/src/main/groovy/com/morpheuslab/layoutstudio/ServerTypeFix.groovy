package com.morpheuslab.layoutstudio

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ContainerType
import groovy.util.logging.Slf4j

/** Sets the server type of each node set, because Morpheus' clone gives them the wrong one. */
@Slf4j
class ServerTypeFix {

    /** sets: [id, nodeTypeId, serverType code] per node set. Returns sets changed. */
    static int apply(MorpheusContext morpheus, List<Map> sets) {
        int fixed = 0
        sets.each { Map want ->
            ComputeServerType type = morpheus.async.cloud.findComputeServerTypeByCode(want.serverType as String).blockingGet()
            ComputeTypeSet s = morpheus.services.computeTypeSet.get(want.id as Long)
            if (!type || !s) return
            // the plugin model leaves some links empty; fill them so the save does not clear them
            if (!s.containerType) s.containerType = new ContainerType(id: want.nodeTypeId as Long)
            s.computeServerType = type
            morpheus.services.computeTypeSet.save(s)
            fixed++
        }
        log.info("HKS Layout Studio: server types set on ${fixed} node sets ${sets*.serverType}")
        fixed
    }
}
