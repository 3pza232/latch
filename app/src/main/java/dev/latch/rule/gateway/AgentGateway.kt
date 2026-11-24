package dev.latch.rule.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import dev.latch.rule.core.log.EventAction
import dev.latch.rule.core.log.EventLevel
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.core.log.EventSource
import dev.latch.rule.core.gateway.PairingTicket
import dev.latch.rule.core.log.LatchEvent
import dev.latch.rule.domain.RuleCoordinator
import dev.latch.rule.privilege.PrivilegeBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Owns the local HTTP gateway and the one-time pairing code.
 * The server only listens while the user has explicitly enabled pairing.
 */
class AgentGateway(
    private val context: Context,
    private val coordinator: RuleCoordinator,
    private val privilege: PrivilegeBroker,
    private val eventLog: EventLog = EventLog.Noop,
) {

    enum class Status { IDLE, WAITING, CONNECTED, ERROR }

    data class State(
        val status: Status = Status.IDLE,
        val code: String? = null,
        val address: String? = null,
        val port: Int = 0,
        val clients: Int = 0,
        /** One string the user can hand to an agent: octet + port + code. */
        val ticket: String? = null,
        /** Why the gateway could not start; shown instead of an idle panel. */
        val error: String? = null,
    )

    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versionName: String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: GatewayServer? = null
    private var token: String? = null
    private var currentCode: String? = null
    private var failures = 0

    /** Starts listening; returns false and publishes [Status.ERROR] when the port is unusable. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        failures = 0
        token = null
        currentCode = "%06d".format(random.nextInt(1_000_000))
        val created = GatewayServer(this, coordinator, privilege, versionName, eventLog)
        val started = runCatching { created.start(2_000, false) }
        if (started.isFailure) {
            runCatching { created.stop() }
            server = null
            currentCode = null
            val reason = started.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: "无法监听端口"
            _state.value = State(status = Status.ERROR, error = reason)
            record(EventLevel.ERROR, EventAction.GATEWAY_START, "failed", reason)
            return false
        }
        server = created
        val address = localAddress()
        val port = created.listeningPort
        val code = currentCode
        _state.value = State(
            status = Status.WAITING,
            code = code,
            address = address,
            port = port,
            ticket = ticketFor(address, port, code),
        )
        record(EventLevel.INFO, EventAction.GATEWAY_START, "waiting", detail = "port=$port")
        return true
    }

    /** Null when the address is not IPv4, in which case the client needs the pieces separately. */
    private fun ticketFor(address: String?, port: Int, code: String?): String? {
        if (address == null || code == null) return null
        val octet = address.substringAfterLast('.').toIntOrNull() ?: return null
        return runCatching { PairingTicket(octet, port, code).encode() }.getOrNull()
    }

    @Synchronized
    fun stop() {
        val wasRunning = server != null
        server?.stop()
        server = null
        token = null
        currentCode = null
        failures = 0
        _state.value = State()
        if (wasRunning) record(EventLevel.INFO, EventAction.GATEWAY_STOP, "stopped")
    }

    /** Returns (token, error). Consumed code is invalidated after a successful pair. */
    @Synchronized
    fun pair(input: String): Pair<String?, String?> {
        val expected = currentCode ?: return null to "gateway is not waiting for a client"
        if (failures >= MAX_FAILURES) return null to "too many attempts, restart pairing"
        if (input != expected) {
            failures++
            if (failures >= MAX_FAILURES) currentCode = "%06d".format(random.nextInt(1_000_000))
            record(EventLevel.WARN, EventAction.PAIR_FAILED, "rejected", detail = "failures=$failures")
            return null to "invalid pairing code"
        }
        val newToken = ByteArray(32).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        token = newToken
        _state.value = _state.value.copy(status = Status.CONNECTED, clients = 1)
        record(EventLevel.INFO, EventAction.PAIR, "connected")
        return newToken to null
    }

    @Synchronized
    fun isAuthorized(header: String?): Boolean {
        val expected = token ?: return false
        return header == "Bearer $expected"
    }

    private fun record(
        level: EventLevel,
        action: EventAction,
        outcome: String,
        message: String? = null,
        detail: String? = null,
    ) {
        scope.launch {
            eventLog.record(
                LatchEvent(
                    level = level,
                    source = EventSource.GATEWAY,
                    action = action,
                    outcome = outcome,
                    message = message,
                    detail = detail,
                ),
            )
        }
    }

    private fun localAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val properties: LinkProperties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }

    private companion object {
        const val MAX_FAILURES = 5
    }
}
