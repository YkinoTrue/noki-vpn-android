package com.noki.vpn

import com.noki.vpn.data.BackendPayment
import com.noki.vpn.data.BackendPaymentConfig
import com.noki.vpn.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

data class PaymentCheckoutState(
    val config: BackendPaymentConfig? = null,
    val methodCode: String? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isChecking: Boolean = false,
    val payment: BackendPayment? = null,
    val launchUrl: String? = null,
    val error: String? = null,
    val result: PaymentCheckoutResult? = null,
)

enum class PaymentCheckoutResult {
    SUCCESS,
    FAILURE,
}

internal fun isSafePaymentUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)

/** Owns the native checkout lifecycle. Paid status is accepted only from the account API. */
internal class PaymentCheckoutWorkflow(
    private val scope: CoroutineScope,
    private val currentState: () -> AppUiState,
    private val publishState: (AppUiState) -> Unit,
    private val currentAuthAttempt: () -> AuthSessionAttempt?,
    private val isCurrent: (AuthSessionAttempt) -> Boolean,
    private val loadConfig: suspend () -> BackendPaymentConfig,
    private val create: suspend (AuthSessionAttempt, String, Int?) -> BackendPayment,
    private val loadPayments: suspend (AuthSessionAttempt) -> List<BackendPayment>,
    private val restorePaymentId: () -> String?,
    private val savePaymentId: (String?) -> Unit,
    private val onPaid: () -> Unit,
    private val pollDelayMillis: Long = 3_000,
) {
    private var generation = 0L
    private var configJob: Job? = null
    private var submitJob: Job? = null
    private var statusJob: Job? = null
    private var statusRevision = 0L
    private val state get() = currentState().paymentCheckout

    private fun update(value: PaymentCheckoutState) {
        publishState(currentState().copy(paymentCheckout = value))
    }

    fun prepare() {
        if (configJob != null) return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        update(state.copy(isLoading = true, error = null))
        configJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val config = loadConfig()
                if (owner != generation || !isCurrent(attempt)) return@launch
                val selected = state.methodCode?.takeIf { code -> config.methods.any { it.code == code && it.enabled } }
                    ?: config.methods.firstOrNull { it.enabled }?.code
                update(state.copy(config = config, methodCode = selected, isLoading = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == generation && isCurrent(attempt)) update(state.copy(isLoading = false, error = readable(error)))
            } finally {
                if (owner == generation) configJob = null
            }
        }.also { it.start() }
    }

    fun selectMethod(code: String) {
        if (submitJob != null || state.config?.methods?.none { it.code == code && it.enabled } != false) return
        update(state.copy(methodCode = code, error = null))
    }

    fun submit(planCode: String) {
        if (submitJob != null || currentState().currentDeviceAccessRole.equals("invited", true)) return
        val config = state.config ?: return
        val method = config.methods.firstOrNull { it.code == state.methodCode && it.enabled } ?: return
        if (!config.configured || !config.checkoutEnabled) return
        if (currentState().plans.none { it.code == planCode && it.monthlyPriceRub > 0 }) return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        stopChecking()
        update(state.copy(isSubmitting = true, error = null, launchUrl = null, result = null))
        submitJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val payment = create(attempt, planCode, method.id)
                if (owner != generation || !isCurrent(attempt)) return@launch
                require(payment.planCode == planCode && payment.publicId.isNotBlank()) { "invalid_payment_response" }
                savePaymentId(payment.publicId)
                when (payment.status.lowercase()) {
                    "paid" -> {
                        update(state.copy(
                            payment = payment,
                            isSubmitting = false,
                            result = PaymentCheckoutResult.SUCCESS,
                        ))
                        onPaid()
                    }
                    "pending" -> {
                        update(state.copy(payment = payment, isSubmitting = false))
                        reopen()
                    }
                    else -> update(state.copy(
                        payment = payment,
                        isSubmitting = false,
                        result = PaymentCheckoutResult.FAILURE,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == generation && isCurrent(attempt)) update(state.copy(isSubmitting = false, error = readable(error)))
            } finally {
                if (owner == generation) submitJob = null
            }
        }.also { it.start() }
    }

    fun reopen() {
        val payment = state.payment ?: return
        val url = payment.paymentUrl
        if (payment.status != "pending") return
        if (url == null || !isSafePaymentUrl(url)) {
            browserFailed()
            return
        }
        update(state.copy(launchUrl = url, error = null))
    }

    fun consumeLaunchUrl() = update(state.copy(launchUrl = null))

    fun dismissResult() = update(state.copy(result = null))

    fun browserFailed() = update(state.copy(launchUrl = null, error = tr(
        currentState().personalizationSettings.language,
        "Не удалось открыть платёжную страницу. Попробуйте открыть её ещё раз.",
        "Could not open the payment page. Please try opening it again.",
    )))

    fun refreshStatus() {
        if (statusJob != null || submitJob != null) return
        val id = state.payment?.publicId ?: restorePaymentId() ?: return
        val attempt = currentAuthAttempt() ?: return
        val owner = generation
        update(state.copy(isChecking = true, error = null))
        val revision = ++statusRevision
        statusJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                repeat(10) { index ->
                    val payment = loadPayments(attempt).firstOrNull { it.publicId == id }
                    if (owner != generation || revision != statusRevision || !isCurrent(attempt)) return@launch
                    if (payment == null) {
                        savePaymentId(null)
                        update(state.copy(payment = null, isChecking = false))
                        return@launch
                    }
                    val previousStatus = state.payment?.status?.lowercase()
                    val status = payment.status.lowercase()
                    val result = when {
                        status == "paid" && previousStatus != "paid" -> PaymentCheckoutResult.SUCCESS
                        status in PAYMENT_FAILURE_STATUSES && previousStatus != status -> PaymentCheckoutResult.FAILURE
                        else -> state.result
                    }
                    update(state.copy(payment = payment, result = result))
                    if (status == "paid" && previousStatus != "paid") onPaid()
                    if (status != "pending") return@launch
                    if (index < 9) delay(pollDelayMillis)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owner == generation && revision == statusRevision && isCurrent(attempt)) update(state.copy(error = readable(error)))
            } finally {
                if (owner == generation && revision == statusRevision) {
                    if (isCurrent(attempt)) update(state.copy(isChecking = false))
                    statusJob = null
                }
            }
        }.also { it.start() }
    }

    fun stopChecking() {
        statusRevision++
        statusJob?.cancel()
        statusJob = null
        update(state.copy(isChecking = false))
    }

    fun invalidate() {
        generation++
        configJob?.cancel()
        configJob = null
        submitJob?.cancel()
        submitJob = null
        stopChecking()
        savePaymentId(null)
        update(PaymentCheckoutState())
    }

    private fun readable(error: Exception): String = AppErrorMapper.readableNetworkError(
        currentState().personalizationSettings.language, error,
    )
}

private val PAYMENT_FAILURE_STATUSES = setOf("failed", "canceled", "refunded")
