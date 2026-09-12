package com.noki.vpn

import com.noki.vpn.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PaymentCheckoutWorkflowTest {
    private val plan = PlanSummary("pro_yearly", "pro", "Pro", 6, 4000.0, "4 TB", 500, 400, features = emptyList())
    private val config = BackendPaymentConfig(true, true, listOf(
        BackendPaymentMethod(null, "card", "Card", true),
        BackendPaymentMethod(2, "sbp", "SBP", true),
        BackendPaymentMethod(13, "crypto", "Crypto", false),
    ))
    private val pending = BackendPayment("invoice-1", "pro_yearly", "pending", 4800, "https://pay.example/checkout")

    private inner class Fixture(scope: CoroutineScope) {
        var state = AppUiState(plans = listOf(plan))
        var savedId: String? = null
        var paid = 0
        var calls = 0
        var requested: Pair<String, Int?>? = null
        var response: suspend () -> BackendPayment = { pending }
        var status: suspend () -> List<BackendPayment> = { listOf(pending) }
        val workflow = PaymentCheckoutWorkflow(scope, { state }, { state = it },
            { AuthSessionAttempt("access", 0) }, { true }, { config },
            { _, code, method -> calls++; requested = code to method; response() },
            { status() }, { savedId }, { savedId = it }, { paid++ }, pollDelayMillis = 1)
    }

    @Test fun `double tap creates one invoice with exact yearly code and selected server method`() = runBlocking {
        val f = Fixture(this)
        f.workflow.prepare(); yield()
        f.workflow.selectMethod("crypto")
        assertEquals("card", f.state.paymentCheckout.methodCode)
        f.workflow.selectMethod("sbp")
        val gate = CompletableDeferred<BackendPayment>()
        f.response = { gate.await() }
        f.workflow.submit(plan.code); f.workflow.submit(plan.code); yield()
        assertEquals(1, f.calls)
        assertEquals(plan.code to 2, f.requested)
        gate.complete(pending); yield()
        assertEquals(pending.paymentUrl, f.state.paymentCheckout.launchUrl)
        assertEquals(pending.publicId, f.savedId)
        assertEquals(0, f.paid)
    }

    @Test fun `logout discards an uncancellable old account response`() = runBlocking {
        val f = Fixture(this)
        f.workflow.prepare(); yield()
        val gate = CompletableDeferred<BackendPayment>()
        f.response = { withContext(NonCancellable) { gate.await() } }
        f.workflow.submit(plan.code); yield()
        f.workflow.invalidate()
        gate.complete(pending); yield(); yield()
        assertNull(f.savedId)
        assertNull(f.state.paymentCheckout.payment)
        assertNull(f.state.paymentCheckout.launchUrl)
    }

    @Test fun `restored pending invoice becomes paid only after account API confirmation`() = runBlocking {
        val f = Fixture(this)
        f.savedId = pending.publicId
        f.status = { listOf(pending.copy(status = "paid")) }
        f.workflow.refreshStatus(); yield()
        assertEquals("paid", f.state.paymentCheckout.payment?.status)
        assertEquals(PaymentCheckoutResult.SUCCESS, f.state.paymentCheckout.result)
        assertEquals(1, f.paid)
        f.workflow.refreshStatus(); yield()
        assertEquals(1, f.paid)
        assertNull(f.state.paymentCheckout.launchUrl)
        assertFalse(f.state.paymentCheckout.isChecking)
    }

    @Test fun `declined invoice and invalid URL never activate subscription`() = runBlocking {
        val f = Fixture(this)
        f.workflow.prepare(); yield()
        f.response = { pending.copy(paymentUrl = "intent://malicious") }
        f.workflow.submit(plan.code); yield()
        assertNull(f.state.paymentCheckout.launchUrl)
        assertNotNull(f.state.paymentCheckout.error)
        f.status = { listOf(pending.copy(status = "failed")) }
        f.workflow.refreshStatus(); yield()
        assertEquals("failed", f.state.paymentCheckout.payment?.status)
        assertEquals(PaymentCheckoutResult.FAILURE, f.state.paymentCheckout.result)
        assertEquals(0, f.paid)
    }

    @Test fun `invited device and unknown plans cannot create payments`() = runBlocking {
        val f = Fixture(this)
        f.workflow.prepare(); yield()
        f.workflow.submit("unknown")
        f.state = f.state.copy(currentDeviceAccessRole = "invited")
        f.workflow.submit(plan.code); yield()
        assertEquals(0, f.calls)
    }

    @Test fun `browser URL permits HTTPS only without credentials or nonstandard port`() {
        assertTrue(isSafePaymentUrl("https://pay.example/pay?id=123"))
        listOf("http://pay.example", "javascript:alert(1)", "file:///secret", "https://user@pay.example", "https://pay.example:8080", "//pay.example").forEach {
            assertFalse(it, isSafePaymentUrl(it))
        }
    }
}
