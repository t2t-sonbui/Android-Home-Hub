package com.rcswitchcontrol.zigbee.protocol

import com.zsmartsystems.zigbee.ZigBeeNetworkManager
import com.zsmartsystems.zigbee.ZigBeeStatus
import com.zsmartsystems.zigbee.app.ZigBeeNetworkExtension
import com.zsmartsystems.zigbee.app.discovery.ZigBeeDiscoveryExtension

class LazyDiscoveryExtension : ZigBeeNetworkExtension {

    private var networkManager: ZigBeeNetworkManager? = null

    override fun extensionInitialize(networkManager: ZigBeeNetworkManager?): ZigBeeStatus {
        this.networkManager = networkManager
        return ZigBeeStatus.SUCCESS
    }

    override fun extensionStartup(): ZigBeeStatus {
        val backing = networkManager?.getExtension(ZigBeeDiscoveryExtension::class.java)
                as? ZigBeeDiscoveryExtension
                ?: return ZigBeeStatus.INVALID_STATE

//        backing.setUpdateOnChange(true)
        backing.updatePeriod = ZigBeeProtocol.MESH_UPDATE_PERIOD
        backing.refresh()

        return ZigBeeStatus.SUCCESS
    }

    override fun extensionShutdown() = Unit
}