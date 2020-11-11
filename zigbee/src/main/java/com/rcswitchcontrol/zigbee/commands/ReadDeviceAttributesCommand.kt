/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.rcswitchcontrol.zigbee.commands

import android.util.Log
import com.rcswitchcontrol.protocols.models.Payload
import com.rcswitchcontrol.zigbee.R
import com.rcswitchcontrol.zigbee.models.ZigBeeAttribute
import com.rcswitchcontrol.zigbee.protocol.ZigBeeProtocol
import com.rcswitchcontrol.zigbee.protocol.appendZigBeeCommands
import com.tunjid.rcswitchcontrol.common.ContextProvider
import com.tunjid.rcswitchcontrol.common.serialize
import com.zsmartsystems.zigbee.CommandResult
import com.zsmartsystems.zigbee.ZigBeeEndpoint
import com.zsmartsystems.zigbee.ZigBeeNetworkManager
import com.zsmartsystems.zigbee.zcl.ZclCluster
import com.zsmartsystems.zigbee.zcl.ZclStatus
import com.zsmartsystems.zigbee.zcl.clusters.general.ReadAttributesResponse
import com.zsmartsystems.zigbee.zcl.protocol.ZclClusterType
import java.io.PrintStream
import java.util.concurrent.ExecutionException


/**
 * Switches a device on.
 */
class ReadDeviceAttributesCommand : AbsZigBeeCommand(), PayloadPublishing {
    override val args: String = "ENDPOINT CLUSTER ATTRIBUTE1 [ATTRIBUTE2 ...]"

    override fun getCommand(): String = ContextProvider.appContext.getString(R.string.zigbeeprotocol_device_attributes)

    override fun getDescription(): String = "Read one or more attributes from a device"

    @Throws(IllegalArgumentException::class, InterruptedException::class, ExecutionException::class)
    override fun process(networkManager: ZigBeeNetworkManager?, args: Array<String>, out: PrintStream) {
        require(args.size >= 4) { "Invalid number of arguments" }

        val endpoint: ZigBeeEndpoint = getEndpoint(networkManager, args[1])
        val cluster: ZclCluster = getCluster(endpoint, args[2])

        out.println(Payload(
                key = ZigBeeProtocol::class.java.name,
                action = command,
                data = cluster.pullAttributes(attributeIds = args
                        .drop(3)
                        .filterNot { it.contains("=") }
                        .mapNotNull(String::toIntOrNull))
                        .serialize()
        )
                .appendZigBeeCommands()
                .serialize()
        )
    }
}

private fun ZclCluster.pullAttributes(attributeIds: List<Int>): List<ZigBeeAttribute> =
        when (val response = readAttributes(attributeIds)
                .get()
                .takeIf(CommandResult::isSuccess)
                ?.getResponse<ReadAttributesResponse>()) {
            null -> listOf()
            else -> response.records
                    .filter { it.status == ZclStatus.SUCCESS }
                    .map {
                        ZigBeeAttribute(
                                id = it.attributeIdentifier,
                                endpointId = zigBeeAddress.endpoint,
                                clusterId = clusterId,
                                type = it.attributeDataType.dataClass.simpleName,
                                value = it.attributeValue
                        ).also { attr ->
                            Log.i("TEST", "${ZclClusterType.values().firstOrNull { it.id == attr.clusterId }}: ${attr.value}")
                        }
                    }
        }