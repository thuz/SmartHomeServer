package com.dehnes.smarthome.api

import com.dehnes.smarthome.VideoBrowser
import com.dehnes.smarthome.api.dtos.*
import com.dehnes.smarthome.api.dtos.RequestType.*
import com.dehnes.smarthome.configuration
import com.dehnes.smarthome.datalogging.QuickStatsService
import com.dehnes.smarthome.energy_consumption.EnergyConsumptionService
import com.dehnes.smarthome.energy_pricing.EnergyPriceService
import com.dehnes.smarthome.environment_sensors.EnvironmentSensorService
import com.dehnes.smarthome.ev_charging.EvChargingService
import com.dehnes.smarthome.ev_charging.FirmwareUploadService
import com.dehnes.smarthome.firewall_router.BlockedMacs
import com.dehnes.smarthome.firewall_router.DnsBlockingService
import com.dehnes.smarthome.firewall_router.FirewallService
import com.dehnes.smarthome.firewall_router.FirewallState
import com.dehnes.smarthome.garage.*
import com.dehnes.smarthome.heating.UnderFloorHeaterService
import com.dehnes.smarthome.users.UserSettingsService
import com.dehnes.smarthome.utils.withLogging
import com.dehnes.smarthome.victron.DalyBmsDataLogger
import com.dehnes.smarthome.victron.ESSState
import com.dehnes.smarthome.victron.VictronEssProcess
import com.dehnes.smarthome.zwave.StairsHeatingService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.Session
import java.io.Closeable
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// one instance per sessions
class WebSocketServer : Endpoint() {

    private val instanceId = UUID.randomUUID().toString()

    private val objectMapper = configuration.getBean<ObjectMapper>()
    private val logger = KotlinLogging.logger { }
    private val garageLightController = configuration.getBean<GarageLightController>()
    private val underFloopHeaterService = configuration.getBean<UnderFloorHeaterService>()
    private val subscriptions = mutableMapOf<String, Closeable>()
    private val evChargingService = configuration.getBean<EvChargingService>()
    private val firmwareUploadService = configuration.getBean<FirmwareUploadService>()
    private val loRaSensorBoardService = configuration.getBean<EnvironmentSensorService>()
    private val videoBrowser = configuration.getBean<VideoBrowser>()
    private val quickStatsService = configuration.getBean<QuickStatsService>()
    private val victronEssProcess = configuration.getBean<VictronEssProcess>()
    private val energyPriceService = configuration.getBean<EnergyPriceService>()
    private val userSettingsService = configuration.getBean<UserSettingsService>()
    private val energyConsumptionService = configuration.getBean<EnergyConsumptionService>()
    private val dalyBmsDataLogger = configuration.getBean<DalyBmsDataLogger>()
    private val stairsHeatingService = configuration.getBean<StairsHeatingService>()
    private val firewallService = configuration.getBean<FirewallService>()
    private val dnsBlockingService = configuration.getBean<DnsBlockingService>()
    private val blockedMacs = configuration.getBean<BlockedMacs>()
    private val hoermannE4Controller = configuration.getBean<HoermannE4Controller>()
    private val garageVentilationController = configuration.getBean<GarageVentilationController>()

    override fun onOpen(sess: Session, p1: EndpointConfig?) {
        logger.info { "$instanceId Socket connected: $sess" }
        sess.addMessageHandler(String::class.java) { msg ->
            withLogging {
                onWebSocketText(sess, msg)
            }.run()
        }
    }

    override fun onClose(session: Session, closeReason: CloseReason) {
        subscriptions.values.toList().forEach { it.close() }
        logger.info { "$instanceId Socket Closed: $closeReason" }
    }

    override fun onError(session: Session?, cause: Throwable?) {
        logger.warn(cause) { instanceId }
    }

    private fun errorCatching(fn: () -> RpcResponse) = try {
        fn()
    } catch (e: Exception) {
        RpcResponse(errorMsg = e.localizedMessage)
    }

    private fun onWebSocketText(argSession: Session, argMessage: String) {
        val userEmail = argSession.userProperties["userEmail"] as String?
        val websocketMessage: WebsocketMessage = objectMapper.readValue(argMessage)

        if (websocketMessage.type != WebsocketMessageType.rpcRequest) {
            return
        }

        val rpcRequest = websocketMessage.rpcRequest!!
        val response: RpcResponse = when (rpcRequest.type) {
            garageVentilationRequest -> {
                garageVentilationController.setMilliVolts(userEmail, rpcRequest.garageVentilationCommandMilliVolts!!)
                RpcResponse(
                    garageVentilationState = garageVentilationController.getCurrent(userEmail)
                )
            }
            sendHoermannE4Command -> errorCatching {

                val cnt = CountDownLatch(1)
                var sendResult = false
                hoermannE4Controller.send(
                    rpcRequest.hoermannE4Command!!
                ) {
                    sendResult = it
                    cnt.countDown()
                }

                check(cnt.await(1, TimeUnit.SECONDS))

                RpcResponse(hoermannE4CommandResult = sendResult)
            }

            blockedMacsSet -> errorCatching {
                blockedMacs.set(userEmail, rpcRequest.blockedMacs!!)
                RpcResponse()
            }

            dnsBlockingSet -> errorCatching {
                dnsBlockingService.set(userEmail, rpcRequest.dnsBlockingLists!!)
                RpcResponse()
            }

            dnsBlockingUpdateStandardLists -> errorCatching {
                dnsBlockingService.updateStandardLists(userEmail)
                RpcResponse()
            }

            stairsHeatingRequest -> {
                RpcResponse(
                    stairsHeatingResponse = stairsHeatingService.handleRequest(
                        userEmail,
                        rpcRequest.stairsHeatingRequest!!
                    )
                )
            }

            writeBms -> {
                dalyBmsDataLogger.write(userEmail, rpcRequest.writeBms!!)
                RpcResponse()
            }

            userSettings -> RpcResponse(userSettings = userSettingsService.getUserSettings(userEmail))
            readAllUserSettings -> RpcResponse(allUserSettings = userSettingsService.getAllUserSettings(userEmail))
            writeUserSettings -> {
                userSettingsService.handleWrite(userEmail, rpcRequest.writeUserSettings!!)
                RpcResponse(allUserSettings = userSettingsService.getAllUserSettings(userEmail))
            }

            essRead -> RpcResponse(essState = victronEssProcess.current(userEmail))
            essWrite -> {
                victronEssProcess.handleWrite(userEmail, rpcRequest.essWrite!!)
                RpcResponse(essState = victronEssProcess.current(userEmail))
            }

            quickStats -> RpcResponse(quickStatsResponse = quickStatsService.getStats())
            energyConsumptionQuery -> RpcResponse(energyConsumptionData = energyConsumptionService.report(rpcRequest.energyConsumptionQuery!!))
            subscribe -> {
                val subscribe = rpcRequest.subscribe!!
                val subscriptionId = subscribe.subscriptionId

                val existing = subscriptions[subscriptionId]
                if (existing == null) {
                    val sub = when (subscribe.type) {

                        SubscriptionType.getGarageVentilationStatus -> {
                            val onEvent = { e: GarageVentilationState ->
                                argSession.basicRemote.sendText(
                                    objectMapper.writeValueAsString(
                                        WebsocketMessage(
                                            UUID.randomUUID().toString(),
                                            WebsocketMessageType.notify,
                                            notify = Notify(
                                                subscriptionId,
                                                garageVentilationState = e
                                            )
                                        )
                                    )
                                )
                            }

                            garageVentilationController.addListener(userEmail, subscriptionId) { onEvent(it) }
                            garageVentilationController.getCurrent(userEmail)?.let {
                                onEvent(it)
                            }

                            object : Closeable {
                                override fun close() {
                                    garageVentilationController.removeListener(subscriptionId)
                                }
                            }
                        }

                        SubscriptionType.getGarageDoorStatus -> {
                            val onEvent = {e: HoermannE4Broadcast ->
                                argSession.basicRemote.sendText(
                                    objectMapper.writeValueAsString(
                                        WebsocketMessage(
                                            UUID.randomUUID().toString(),
                                            WebsocketMessageType.notify,
                                            notify = Notify(
                                                subscriptionId,
                                                hoermannE4Broadcast = e
                                            )
                                        )
                                    )
                                )
                            }

                            hoermannE4Controller.addListener(userEmail, subscriptionId) { onEvent(it) }
                            onEvent(hoermannE4Controller.getCurrent())
                            object : Closeable {
                                override fun close() {
                                    hoermannE4Controller.removeListener(subscriptionId)
                                }
                            }
                        }

                        SubscriptionType.firewall -> {

                            val state = firewallService.currentState

                            val sub = object : Subscription<FirewallState>(subscriptionId, argSession) {
                                override fun onEvent(e: FirewallState) {
                                    argSession.basicRemote.sendText(
                                        objectMapper.writeValueAsString(
                                            WebsocketMessage(
                                                UUID.randomUUID().toString(),
                                                WebsocketMessageType.notify,
                                                notify = Notify(
                                                    subscriptionId,
                                                    firewallState = e,
                                                )
                                            )
                                        )
                                    )
                                }

                                override fun close() {
                                    firewallService.listeners.remove(subscriptionId)
                                }

                            }

                            firewallService.listeners[subscriptionId] = sub::onEvent
                            sub.apply {
                                this.onEvent(state)
                            }
                        }

                        SubscriptionType.essState -> EssSubscription(subscriptionId, argSession).apply {
                            victronEssProcess.listeners[subscriptionId] = this::onEvent
                        }

                        SubscriptionType.quickStatsEvents -> QuickStatsSubscription(subscriptionId, argSession).apply {
                            quickStatsService.listeners[subscriptionId] = this::onEvent
                        }

                        SubscriptionType.getGarageLightStatus -> GarageLightStatusSubscription(subscriptionId, argSession).apply {
                            garageLightController.addListener(userEmail, subscriptionId, this::onEvent)
                        }

                        SubscriptionType.getUnderFloorHeaterStatus -> UnderFloorHeaterSubscription(
                            subscriptionId, argSession
                        ).apply {
                            underFloopHeaterService.addListener(userEmail, subscriptionId, this::onEvent)
                        }

                        SubscriptionType.evChargingStationEvents -> EvChargingStationSubscription(
                            subscriptionId,
                            argSession,
                        ).apply {
                            evChargingService.addListener(userEmail, subscriptionId, this::onEvent)
                        }

                        SubscriptionType.environmentSensorEvents -> EnvironmentSensorSubscription(
                            subscriptionId,
                            argSession,
                        ).apply {
                            loRaSensorBoardService.addListener(userEmail, subscriptionId, this::onEvent)
                        }
                    }

                    subscriptions.put(subscriptionId, sub)?.close()
                    logger.info { "$instanceId New subscription id=$subscriptionId type=${subscribe.type}" }
                } else {
                    logger.info { "$instanceId re-subscription id=$subscriptionId type=${subscribe.type}" }
                }

                RpcResponse(subscriptionCreated = true)
            }

            unsubscribe -> {
                val subscriptionId = rpcRequest.unsubscribe!!.subscriptionId
                subscriptions.remove(subscriptionId)?.close()
                logger.info { "$instanceId Removed subscription id=$subscriptionId" }
                RpcResponse(subscriptionRemoved = true)
            }

            garageLightRequest -> RpcResponse(garageLightResponse = garageLightRequest(rpcRequest.garageLightRequest!!, userEmail))
            underFloorHeaterRequest -> RpcResponse(
                underFloorHeaterResponse = underFloorHeaterRequest(
                    userEmail,
                    rpcRequest.underFloorHeaterRequest!!
                )
            )

            evChargingStationRequest -> RpcResponse(
                evChargingStationResponse = evChargingStationRequest(
                    userEmail,
                    rpcRequest.evChargingStationRequest!!
                )
            )

            environmentSensorRequest -> RpcResponse(
                environmentSensorResponse = environmentSensorRequest(
                    userEmail,
                    rpcRequest.environmentSensorRequest!!
                )
            )

            RequestType.videoBrowser -> RpcResponse(
                videoBrowserResponse = videoBrowser.rpc(
                    userEmail,
                    rpcRequest.videoBrowserRequest!!
                )
            )

            readEnergyPricingSettings -> RpcResponse(
                energyPricingSettingsRead = EnergyPricingSettingsRead(
                    energyPriceService.getAllSettings(userEmail),
                )
            )

            writeEnergyPricingSettings -> {
                val req = rpcRequest.energyPricingSettingsWrite!!
                if (req.neutralSpan != null) {
                    energyPriceService.setNeutralSpan(userEmail, req.service, req.neutralSpan)
                }
                if (req.avgMultiplier != null) {
                    energyPriceService.setAvgMultiplier(userEmail, req.service, req.avgMultiplier)
                }
                RpcResponse(
                    energyPricingSettingsRead = EnergyPricingSettingsRead(
                        energyPriceService.getAllSettings(userEmail),
                    )
                )
            }
        }

        argSession.basicRemote.sendText(
            objectMapper.writeValueAsString(
                WebsocketMessage(
                    websocketMessage.id,
                    WebsocketMessageType.rpcResponse,
                    null,
                    response,
                    null
                )
            )
        )
    }

    private fun environmentSensorRequest(userEmail: String?, request: EnvironmentSensorRequest) = when (request.type) {
        EnvironmentSensorRequestType.getAllEnvironmentSensorData -> loRaSensorBoardService.getEnvironmentSensorResponse(
            userEmail
        )

        EnvironmentSensorRequestType.scheduleFirmwareUpgrade -> {
            loRaSensorBoardService.firmwareUpgrade(userEmail, request.sensorId!!, true)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.cancelFirmwareUpgrade -> {
            loRaSensorBoardService.firmwareUpgrade(userEmail, request.sensorId!!, false)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.scheduleTimeAdjustment -> {
            loRaSensorBoardService.timeAdjustment(userEmail, request.sensorId, true)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.cancelTimeAdjustment -> {
            loRaSensorBoardService.timeAdjustment(userEmail, request.sensorId, false)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.scheduleReset -> {
            loRaSensorBoardService.configureReset(userEmail, request.sensorId, true)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.cancelReset -> {
            loRaSensorBoardService.configureReset(userEmail, request.sensorId, false)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.adjustSleepTimeInSeconds -> {
            loRaSensorBoardService.adjustSleepTimeInSeconds(
                userEmail,
                request.sensorId!!,
                request.sleepTimeInSecondsDelta!!
            )
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }

        EnvironmentSensorRequestType.uploadFirmware -> {
            loRaSensorBoardService.setFirmware(userEmail, request.firmwareFilename!!, request.firmwareBased64Encoded!!)
            loRaSensorBoardService.getEnvironmentSensorResponse(userEmail)
        }
    }

    private fun evChargingStationRequest(user: String?, request: EvChargingStationRequest) = when (request.type) {
        EvChargingStationRequestType.uploadFirmwareToClient -> EvChargingStationResponse(
            uploadFirmwareToClientResult = firmwareUploadService.uploadVersion(
                user = user,
                clientId = request.clientId!!,
                firmwareBased64Encoded = request.firmwareBased64Encoded!!
            ),
            chargingStationsDataAndConfig = evChargingService.getChargingStationsDataAndConfig(user)
        )

        EvChargingStationRequestType.getChargingStationsDataAndConfig -> EvChargingStationResponse(
            chargingStationsDataAndConfig = evChargingService.getChargingStationsDataAndConfig(user)
        )

        EvChargingStationRequestType.setLoadSharingPriority -> EvChargingStationResponse(
            configUpdated = evChargingService.setPriorityFor(
                user,
                request.clientId!!,
                request.newLoadSharingPriority!!
            ),
            chargingStationsDataAndConfig = evChargingService.getChargingStationsDataAndConfig(user)
        )

        EvChargingStationRequestType.setMode -> EvChargingStationResponse(
            configUpdated = evChargingService.updateMode(user, request.clientId!!, request.newMode!!),
            chargingStationsDataAndConfig = evChargingService.getChargingStationsDataAndConfig(user)
        )

        EvChargingStationRequestType.setChargeRateLimit -> EvChargingStationResponse(
            configUpdated = evChargingService.setChargeRateLimitFor(
                user,
                request.clientId!!,
                request.chargeRateLimit!!
            ),
            chargingStationsDataAndConfig = evChargingService.getChargingStationsDataAndConfig(user)
        )
    }

    private fun underFloorHeaterRequest(userEmail: String?, request: UnderFloorHeaterRequest) = when (request.type) {
        UnderFloorHeaterRequestType.updateMode -> {
            val success = underFloopHeaterService.updateMode(userEmail, request.newMode!!)
            underFloopHeaterService.getCurrentState(userEmail).copy(
                updateUnderFloorHeaterModeSuccess = success
            )
        }

        UnderFloorHeaterRequestType.updateTargetTemperature -> {
            val success = underFloopHeaterService.updateTargetTemperature(userEmail, request.newTargetTemperature!!)
            underFloopHeaterService.getCurrentState(userEmail).copy(
                updateUnderFloorHeaterModeSuccess = success
            )
        }

        UnderFloorHeaterRequestType.getStatus -> underFloopHeaterService.getCurrentState(userEmail)
        UnderFloorHeaterRequestType.adjustTime -> {
            val success = underFloopHeaterService.adjustTime(userEmail)
            underFloopHeaterService.getCurrentState(userEmail).copy(
                adjustTimeSuccess = success
            )
        }

        UnderFloorHeaterRequestType.firmwareUpgrade -> {
            val success = underFloopHeaterService.startFirmwareUpgrade(userEmail, request.firmwareBased64Encoded!!)
            underFloopHeaterService.getCurrentState(userEmail).copy(
                firmwareUploadSuccess = success
            )
        }
    }

    private fun garageLightRequest(request: GarageLightRequest, user: String?): GarageLightResponse {
        val send = { fn: (user: String?, callback: (r: Boolean) -> Unit) -> Unit ->
            val cnt = CountDownLatch(1)
            var sendResult = false
            fn(user) {
                sendResult = it
                cnt.countDown()
            }
            check(cnt.await(1, TimeUnit.SECONDS)) { "garageLightRequest failed/timed out" }

            RpcResponse(hoermannE4CommandResult = sendResult)

            GarageLightResponse(null, sendResult)
        }

        return when (request.type) {
            GarageLightRequestType.getStatus -> GarageLightResponse(garageLightController.getCurrentState(user))
            GarageLightRequestType.setLedStripeMode -> garageLightController.setLedStripeMode(
                user,
                request.setLedStripeMode!!
            )

            GarageLightRequestType.setLedStripeLowMillivolts -> garageLightController.setLedStripeLowMillivolts(
                user,
                request.ledStripeLowMillivolts!!
            )
            GarageLightRequestType.switchOnCeilingLight -> send(garageLightController::switchOnCeilingLight)
            GarageLightRequestType.switchOffCeilingLight -> send(garageLightController::switchOffCeilingLight)
            GarageLightRequestType.switchLedStripeOff -> send(garageLightController::switchLedStripeOff)
            GarageLightRequestType.switchLedStripeOnLow -> send(garageLightController::switchLedStripeOnLow)
            GarageLightRequestType.switchLedStripeOnHigh -> send(garageLightController::switchLedStripeOnHigh)
        }
    }

    inner class GarageLightStatusSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<GarageLightStatus>(subscriptionId, sess) {
        override fun onEvent(e: GarageLightStatus) {
            logger.debug { "$instanceId onEvent GarageLightStatusSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            e,
                        )
                    )
                )
            )
        }

        override fun close() {
            garageLightController.removeListener(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }

    inner class QuickStatsSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<QuickStatsResponse>(subscriptionId, sess) {
        override fun onEvent(e: QuickStatsResponse) {
            logger.debug { "$instanceId onEvent QuickStatsSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            quickStatsResponse = e,
                        )
                    )
                )
            )
        }

        override fun close() {
            quickStatsService.listeners.remove(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }

    inner class EssSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<ESSState>(subscriptionId, sess) {
        override fun onEvent(e: ESSState) {
            logger.debug { "$instanceId onEvent EssSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            essState = e
                        )
                    )
                )
            )
        }

        override fun close() {
            victronEssProcess.listeners.remove(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }

    inner class EnvironmentSensorSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<EnvironmentSensorEvent>(subscriptionId, sess) {
        override fun onEvent(e: EnvironmentSensorEvent) {
            logger.debug { "$instanceId onEvent EnvironmentSensorSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            environmentSensorEvent = e
                        )
                    )
                )
            )
        }

        override fun close() {
            loRaSensorBoardService.removeListener(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }

    inner class EvChargingStationSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<EvChargingEvent>(subscriptionId, sess) {
        override fun onEvent(e: EvChargingEvent) {
            logger.debug { "$instanceId onEvent EvChargingStationSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            null,
                            null,
                            e,
                            null,
                            null,
                            null,
                        )
                    )
                )
            )
        }

        override fun close() {
            evChargingService.removeListener(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }

    inner class UnderFloorHeaterSubscription(
        subscriptionId: String,
        sess: Session,
    ) : Subscription<UnderFloorHeaterResponse>(subscriptionId, sess) {
        override fun onEvent(e: UnderFloorHeaterResponse) {
            logger.debug { "$instanceId onEvent UnderFloorHeaterSubscription $subscriptionId " }
            sess.basicRemote.sendText(
                objectMapper.writeValueAsString(
                    WebsocketMessage(
                        UUID.randomUUID().toString(),
                        WebsocketMessageType.notify,
                        notify = Notify(
                            subscriptionId,
                            null,
                            e,
                            null,
                            null,
                            null,
                            null,
                        )
                    )
                )
            )
        }

        override fun close() {
            underFloopHeaterService.removeListener(subscriptionId)
            subscriptions.remove(subscriptionId)
        }
    }
}

abstract class Subscription<E>(
    val subscriptionId: String,
    val sess: Session,
) : Closeable {
    abstract fun onEvent(e: E)
}

