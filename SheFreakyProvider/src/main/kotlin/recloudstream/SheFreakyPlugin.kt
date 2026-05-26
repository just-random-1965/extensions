package recloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SheFreakyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SheFreakyProvider())
    }
}
