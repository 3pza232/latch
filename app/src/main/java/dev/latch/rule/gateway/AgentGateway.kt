package dev.latch.rule.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import dev.latch.rule.domain.RuleCoordinator
import dev.latch.rule.privilege.PrivilegeBroker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Owns the local HTTP gateway and the one-time pairing code.
 * The server only listens while the user has explicitly enabled pairing.
 */
class AgentGateway(
    private val context: Context,
    private val coordinator: RuleCoordinator,
    private val privilege: PrivilegeBroker,
) {

    enum class Status { IDLE, WAITING, CONNECTED }

    data class State(
        val status: Status = Status.IDLE,
        val code: String? = null,
        val address: String? = null,
        val port: Int = PORT,
        val clients: Int = 0,
    )

    private val random = SecureRandom()
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

    @Synchronized
    fun start() {
        if (server != null) return
        failures = 0
        token = null
        currentCode = "%06d".format(random.nextInt(1_000_000))
        server = GatewayServer(this, coordinator, privilege, versionName).also { it.start(2_000, false) }
        _state.value = State(
            status = Status.WAITING,
            code = currentCode,
            address = localAddress(),
            port = PORT,
        )
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        token = null
        currentCode = null
        failures = 0
        _state.value = State()
    }

    /** Returns (token, error). Consumed code is invalidated after a successful pair. */
    @Synchronized
    fun pair(input: String): Pair<String?, String?> {
        val expected = currentCode ?: return null to "gateway is not waiting for a client"
        if (failures >= MAX_FAILURES) return null to "too many attempts, restart pairing"
        if (input != expected) {
            failures++
            if (failures >= MAX_FAILURES) currentCode = "%06d".format(random.nextInt(1_000_000))
            return null to "invalid pairing code"
        }
        val newToken = ByteArray(32).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        token = newToken
        _state.value = _state.value.copy(status = Status.CONNECTED, clients = 1)
        return newToken to null
    }

    @Synchronized
    fun isAuthorized(header: String?): Boolean {
        val expected = token ?: return false
        return header == "Bearer $expected"
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

    companion object {
        const val PORT = 8765
        private const val MAX_FAILURES = 5
    }
}
