package dev.latch.rule.core.gateway

/**
 * The single string a user hands to an agent instead of address, port and code.
 *
 * Format: last IPv4 octet (3 digits) + port (5 digits) + pairing code (6 digits),
 * for example `02151234986241`. The agent derives the rest of the address from its
 * own IPv4 address, which works whenever both devices share a /24 network.
 *
 * The ticket adds no secret: the pairing code alone still decides whether pairing
 * succeeds.
 */
data class PairingTicket(
    val lastOctet: Int,
    val port: Int,
    val code: String,
) {
    init {
        require(lastOctet in OCTET_RANGE) { "last octet out of range: $lastOctet" }
        require(port in PORT_RANGE) { "port out of range: $port" }
        require(CODE_PATTERN.matches(code)) { "pairing code must be 6 digits" }
    }

    fun encode(): String = "%03d%05d%s".format(lastOctet, port, code)

    companion object {

        const val LENGTH = 14

        private val OCTET_RANGE = 0..255
        private val PORT_RANGE = 1024..65535
        private val CODE_PATTERN = Regex("""\d{6}""")
        private val TICKET_PATTERN = Regex("""\d{14}""")

        /** Returns null for anything that is not a well-formed ticket. */
        fun decode(text: String): PairingTicket? {
            val trimmed = text.trim()
            if (!TICKET_PATTERN.matches(trimmed)) return null
            val octet = trimmed.substring(0, 3).toInt()
            val port = trimmed.substring(3, 8).toInt()
            if (octet !in OCTET_RANGE || port !in PORT_RANGE) return null
            return PairingTicket(octet, port, trimmed.substring(8))
        }
    }
}
