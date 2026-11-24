package dev.latch.rule.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTicketTest {

    @Test
    fun `encodes fixed-width parts into one 14-digit string`() {
        val ticket = PairingTicket(lastOctet = 21, port = 51234, code = "986241")

        assertEquals("02151234986241", ticket.encode())
        assertEquals(PairingTicket.LENGTH, ticket.encode().length)
    }

    @Test
    fun `decode restores every part`() {
        val ticket = PairingTicket.decode("02151234986241")

        assertEquals(PairingTicket(21, 51234, "986241"), ticket)
    }

    @Test
    fun `whitespace around the ticket is ignored`() {
        assertEquals(PairingTicket(21, 51234, "986241"), PairingTicket.decode("  02151234986241\n"))
    }

    @Test
    fun `wrong length or non-digits are rejected`() {
        assertNull(PairingTicket.decode("0215123498624"))
        assertNull(PairingTicket.decode("021512349862411"))
        assertNull(PairingTicket.decode("0215123498624a"))
    }

    @Test
    fun `out of range octet or port is rejected`() {
        assertNull(PairingTicket.decode("25651234986241"))
        assertNull(PairingTicket.decode("02101000986241"))
        assertNull(PairingTicket.decode("02199999986241"))
    }

    @Test
    fun `constructor refuses values that cannot be encoded`() {
        assertInvalid { PairingTicket(256, 51234, "986241") }
        assertInvalid { PairingTicket(21, 80, "986241") }
        assertInvalid { PairingTicket(21, 51234, "12345") }
    }

    private fun assertInvalid(block: () -> Unit) {
        val threw = runCatching(block).isFailure
        assertEquals(true, threw)
    }
}
