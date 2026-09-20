package com.noki.vpn.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.noki.vpn.AppUiState
import com.noki.vpn.AuthStep
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DeviceSession
import com.noki.vpn.data.PersonalizationSettings
import java.io.File
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w512dp-h1108dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveScreenLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun avatarGalleryLabelFitsItsMenu() = render {
        PersonalizationAvatarMenu(AppLanguage.RU, null, false, true, {}, {}, Modifier)
    }.use {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Выбрать из галереи", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        saveImage("avatar-menu")
        val layout = layouts.single()
        assertTrue("Gallery label must not be ellipsized", !layout.isLineEllipsized(0))
        assertEquals("Every character must remain visible", layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun statisticsDayShowsTrafficAndGroupIsCentered() = render {
        StatsScreen(russianState(), liveGlassEnabled = false, showBackground = false)
    }.use {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val today = compose.onNodeWithText("Сегодня").fetchSemanticsNode().boundsInRoot
        val year = compose.onNodeWithText("Год").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, (today.left + year.right) / 2f, 12f)
        compose.onNodeWithContentDescription("Пн: 0 МБ").performClick()
        compose.onNodeWithText("Пн\n0 МБ").assertIsDisplayed()
        Unit
    }

    @Test
    fun serverSheetClosesFromListContent() {
        var collapsed = false
        render {
            HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, 1f, AppLanguage.RU,
                emptyList(), null, null, false, false, { collapsed = true }, { _, _ -> },
                com.noki.vpn.data.UserProfile())
        }.use {
            compose.onNodeWithText("Автовыбор").assertIsDisplayed()
            compose.onRoot().performTouchInput { swipeUp(startY = height * 0.6f, endY = height * 0.2f) }
            assertTrue("Swipe inside the list must close the sheet", collapsed)
        }
    }

    @Test
    fun countryExpansionKeepsNodeSelectionAndCollapseWorking() {
        val server = com.noki.vpn.data.VpnServer("lv-1", "Latvia 1", "LV", "lv", "example.test", 443, true)
        val country = com.noki.vpn.data.ServerLocation("lv", "LV", "Латвия", "Riga", "example.test", isOnline = true, servers = listOf(server))
        var selected: Pair<String, com.noki.vpn.data.ServerSelectionMode>? = null
        render {
            HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, 1f, AppLanguage.RU,
                listOf(country), null, null, false, false, {}, { id, mode -> selected = id to mode },
                com.noki.vpn.data.UserProfile())
        }.use {
            compose.onNodeWithText("Latvia 1").assertDoesNotExist()
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            compose.onNodeWithText("Latvia 1").assertIsDisplayed().performClick()
            assertEquals("lv-1" to com.noki.vpn.data.ServerSelectionMode.SERVER, selected)
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            compose.onNodeWithText("Latvia 1").assertDoesNotExist()
            compose.onNodeWithText("Латвия").performClick()
            assertEquals("lv" to com.noki.vpn.data.ServerSelectionMode.COUNTRY, selected)
            saveImage("server-country-collapsed")
        }
    }

    @Test
    fun serverListEnds24DpAboveHandleAtDifferentScales() {
        for (scale in listOf(1f, 1.4f)) {
            val countries = (1..10).map { index ->
                com.noki.vpn.data.ServerLocation("c$index", "LV", "Страна $index", "Riga", "example.test", isOnline = true)
            }
            render {
                HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, scale, AppLanguage.RU,
                    countries, null, null, false, false, {}, { _, _ -> }, com.noki.vpn.data.UserProfile())
            }.use {
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val list = compose.onNode(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val density = activity.resources.displayMetrics.density
                val handleTop = root.bottom - (NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp +
                    NokiUiKitPolicy.homeServerDropdownHandleHeightDp) * scale * density
                assertEquals("Visible list must reach the handle with a 24dp gap", 24f, (handleTop - list.bottom) / density, 1f)
                saveImage("server-list-extent-$scale")
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun yearlyPriceAndDiscountFitNarrowCard() = render {
        Box(Modifier.width(320.dp).padding(20.dp)) {
            PlanPriceLine(
                com.noki.vpn.data.PlanSummary("pro_yearly", "pro", "Pro", 6, 4000.0, "4 ТБ", 5100, 425, features = emptyList()),
                com.noki.vpn.data.BillingCycle.YEARLY, AppLanguage.RU, 1f,
            )
        }
    }.use {
        val price = compose.onNodeWithText("5100 ₽ / год").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        price.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Annual price must fit on one line", 1, layouts.single().lineCount)
        val discount = compose.onNodeWithText("-15%").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(discount.left >= price.fetchSemanticsNode().boundsInRoot.right)
        saveImage("annual-price-narrow")
    }

    @Test
    fun securityAboutSheetShowsCurrentVersionAndLinks() = render {
        SecurityAboutSheet(AppLanguage.RU, nokiAdaptiveMetrics(512.dp), {})
    }.use {
        compose.onNodeWithText("версия ${com.noki.vpn.BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Условия использования").assertIsDisplayed()
        compose.onNodeWithText("Политика конфиденциальности").assertIsDisplayed()
        compose.onNodeWithContentDescription("Telegram").assertIsDisplayed()
        compose.onNodeWithContentDescription("Сайт Noki").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.Expand,
        )).assertDoesNotExist()
        saveImage("security-about", includeDialogs = true)
    }

    @Test
    fun tallHomeKeepsControlsAboveNavigation() = renderHome().use {
        val status = compose.onNodeWithText("Не подключено").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Connection controls must stay near the bottom navigation", status.center.y > root.height * 0.8f)
        saveImage("home-wide")
    }

    @Test
    fun widePersonalizationUsesAvailableWidth() = checkSettingsWidth("Персонализация", "personalization-wide") {
        PersonalizationScreen(
            state = russianState(), onLanguageChanged = {}, onGlassModeChanged = {},
            onPickAvatarClicked = {}, onDeleteAvatarClicked = {}, onAvatarEditDenied = {},
            sharedBackdrop = rememberLayerBackdrop(), liveGlassEnabled = false, showBackground = false,
        )
    }

    @Test
    fun wideSupportUsesAvailableWidth() = checkSettingsWidth("Поддержка", "support-wide") {
        SupportScreen(russianState(), rememberLayerBackdrop(), liveGlassEnabled = false, showBackground = false)
    }

    @Test
    fun wideSecurityUsesAvailableWidth() {
        val viewModel = layoutViewModel()
        checkSettingsWidth("Безопасность", "security-wide") {
            SecurityScreen(russianState(), viewModel, {}, rememberLayerBackdrop(), liveGlassEnabled = false, showBackground = false)
        }
    }

    @Test
    fun wideAdvancedSettingsUsesAvailableWidth() {
        val viewModel = layoutViewModel()
        checkSettingsWidth("Расширенные настройки", "advanced-wide") {
            AdvancedSettingsScreen(russianState(), viewModel, rememberLayerBackdrop(), liveGlassEnabled = false, showBackground = false)
        }
    }

    private fun russianState() = com.noki.vpn.SettingsPreparedStatePolicy.withPreparedSettingsState(
        AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
    )

    @Test
    fun emailLoginFieldsScaleWithTheirWidth() {
        val viewModel = layoutViewModel()
        render {
            LoginScreen(russianState().copy(authStep = AuthStep.EMAIL_LOGIN), viewModel, false, {}, {}, {}, {})
        }.use {
            val button = compose.onNode(hasText("Войти") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Login button must keep its design aspect ratio", button.height / button.width > 0.145f)
            saveImage("login-wide")
        }
    }

    @Test
    fun settingsRowsScaleWithTheirWidth() {
        val viewModel = layoutViewModel()
        render {
            SettingsScreen(russianState(), viewModel, rememberLayerBackdrop(), liveGlassEnabled = false,
                showBackground = false, showBottomNavigation = false)
        }.use {
            val row = compose.onNode(hasText("Устройства") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Settings row must keep its design aspect ratio", row.height / row.width > 0.21f)
            saveImage("settings-wide")
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun narrowSettingsRetainOriginalMenuScale() {
        val viewModel = layoutViewModel()
        render {
            SettingsScreen(russianState(), viewModel, rememberLayerBackdrop(), liveGlassEnabled = false,
                showBackground = false, showBottomNavigation = false)
        }.use {
            val row = compose.onNode(hasText("Устройства") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertEquals(80f * activity.resources.displayMetrics.density, row.height, 1f)
            val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Устройства", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            assertEquals(16f, textLayouts.single().layoutInput.style.fontSize.value, 0.01f)
        }
    }

    @Test fun registrationEmailScales() = checkRegistration(AuthStep.REGISTRATION_EMAIL, "Получить код", "registration-email")
    @Test fun registrationCodeScales() = checkRegistration(AuthStep.REGISTRATION_CODE, "Продолжить", "registration-code")
    @Test fun registrationProfileScales() = checkRegistration(AuthStep.REGISTRATION_PROFILE, "Продолжить", "registration-profile")
    @Test fun registrationPasswordScales() = checkRegistration(AuthStep.REGISTRATION_PASSWORD, "Создать аккаунт", "registration-password")

    private fun checkRegistration(step: AuthStep, button: String, image: String) {
        val viewModel = layoutViewModel()
        render { RegistrationScreen(russianState().copy(authStep = step), viewModel, false) }.use {
            checkActionAspect(button)
            saveImage(image)
        }
    }

    @Test fun inviteCodeScales() {
        val viewModel = layoutViewModel()
        render { InviteDeviceScreen(russianState(), viewModel, false) }.use {
            checkActionAspect("Подключить")
            saveImage("invite-code-wide")
        }
    }

    @Test fun changeEmailScales() = checkCredential(com.noki.vpn.AccountSecurityActionState.Email(codeSent = true), "Сменить e-mail", "change-email-wide")
    @Test fun changePasswordScales() = checkCredential(com.noki.vpn.AccountSecurityActionState.Password(), "Задать пароль", "change-password-wide")

    private fun checkCredential(action: com.noki.vpn.AccountSecurityActionState, button: String, image: String) {
        val viewModel = layoutViewModel()
        val state = russianState().copy(accountSecurityState = com.noki.vpn.AccountSecurityUiState(action))
        render { AccountCredentialChangeScreen(state, viewModel, false) }.use {
            checkActionAspect(button)
            saveImage(image)
        }
    }

    private fun checkActionAspect(text: String) {
        val bounds = compose.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Action must scale with its width", bounds.height / bounds.width > 0.145f)
        compose.onNode(hasText(text) and hasClickAction()).assertIsDisplayed()
    }

    @Test fun applicationsScale() {
        val viewModel = layoutViewModel()
        render { AppFilterScreen(russianState(), viewModel, rememberLayerBackdrop(), false, false) }.use {
            checkActionAspect("Применить")
            saveImage("apps-wide")
        }
    }

    @Test fun alwaysOnRulesScale() = checkRules(SiteRulesMode.ALWAYS_ON, "rules-always-wide")
    @Test fun bypassRulesScale() = checkRules(SiteRulesMode.BYPASS, "rules-bypass-wide")
    private fun checkRules(mode: SiteRulesMode, image: String) {
        val viewModel = layoutViewModel()
        render { SiteRulesScreen(russianState(), viewModel, mode, liveGlassEnabled = false, showBackground = false) }.use {
            val action = compose.onNode(hasText("Сохранить") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Rule editor button must scale", action.height > 140f)
            saveImage(image)
        }
    }

    @Test fun statisticsScale() = render { StatsScreen(russianState(), false, false) }.use {
        val header = compose.onNodeWithText("Статистика").fetchSemanticsNode().boundsInRoot
        assertTrue("Statistics title must match the shared scale", header.height > 95f)
        val day = compose.onNodeWithText("Пн").fetchSemanticsNode().boundsInRoot
        val metric = compose.onNodeWithText("Онлайн").fetchSemanticsNode().boundsInRoot
        assertTrue("Chart must follow metrics without an expanding spacer: ${day.top - metric.bottom}px", day.top - metric.bottom < 700f)
        saveImage("stats-wide")
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortPasswordChangeCanScrollToAction() {
        val state = russianState().copy(accountSecurityState = com.noki.vpn.AccountSecurityUiState(
            com.noki.vpn.AccountSecurityActionState.Password()))
        val viewModel = layoutViewModel()
        render { AccountCredentialChangeScreen(state, viewModel, false) }.use {
            compose.onNodeWithText("Задать пароль").performScrollTo().assertIsDisplayed()
            saveImage("change-password-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortRecoveryCanScrollToAction() {
        val state = russianState().let { it.copy(passwordRecoveryForm = it.passwordRecoveryForm.copy(passwordStepVisible = true)) }
        val viewModel = layoutViewModel()
        render { PasswordRecoveryScreen(state, viewModel, false) }.use {
            compose.onNodeWithText("Изменить пароль").performScrollTo().assertIsDisplayed()
            saveImage("recovery-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortRegistrationCanScrollToBack() {
        val viewModel = layoutViewModel()
        render { RegistrationScreen(russianState().copy(authStep = AuthStep.REGISTRATION_PASSWORD), viewModel, false) }.use {
            compose.onNodeWithText("Назад").performScrollTo().assertIsDisplayed()
            saveImage("registration-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortLoginCanScrollToCodeLogin() {
        val viewModel = layoutViewModel()
        render { LoginScreen(russianState().copy(authStep = AuthStep.EMAIL_LOGIN), viewModel, false, {}, {}, {}, {}) }.use {
            compose.onNodeWithText("Войти по коду").performScrollTo().assertIsDisplayed()
            saveImage("login-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h800dp-xxhdpi")
    fun shortStatisticsKeepsChartVisible() = render { StatsScreen(russianState(), false, false) }.use {
        compose.onNodeWithText("Пн").performScrollTo().assertIsDisplayed()
        val day = compose.onNodeWithText("Пн").fetchSemanticsNode().boundsInRoot
        val metric = compose.onNodeWithText("Онлайн").fetchSemanticsNode().boundsInRoot
        assertTrue("Chart needs its full height below the metric grid", day.top - metric.bottom > 200f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Chart must remain above shared navigation", day.bottom < root.bottom - 300f)
        saveImage("stats-short")
    }

    @Test fun avatarCropScalesAndKeepsRotationWorking() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "layout-avatar.png")
        val bitmap = android.graphics.Bitmap.createBitmap(300, 400, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(122, 231, 199))
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var quarterTurns = -1
        render {
            AvatarCropScreen(file.toURI().toString(), AppLanguage.RU, false, null, {},
                { _, _, _, _, _, _, rotation -> quarterTurns = rotation })
        }.use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Загрузка...")).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Повернуть вправо на 90°").performClick()
            val action = compose.onNode(hasText("Сохранить") and hasClickAction())
            assertTrue("Crop action must scale", action.fetchSemanticsNode().boundsInRoot.height > 150f)
            saveImage("avatar-crop-wide")
            action.performClick()
            assertEquals(1, quarterTurns)
        }
        file.delete()
    }

    @Test fun checkoutPaymentMethodsAreExclusive() {
        var appliedPromo = ""
        val plan = com.noki.vpn.data.PlanSummary("pro_yearly", "pro", "Pro", 6, 4000.0, "4 ТБ", 5100, 425,
            features = listOf("4 ТБ", "6 устройств", "Скорость до 200 Мбит/с", "Приватный DNS", "Блокировка рекламы"))
        render {
            var payment by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.noki.vpn.PaymentCheckoutState(
                config = com.noki.vpn.data.BackendPaymentConfig(true, true, listOf(
                    com.noki.vpn.data.BackendPaymentMethod(null, "card", "Карта", true),
                    com.noki.vpn.data.BackendPaymentMethod(2, "sbp", "СБП", true),
                    com.noki.vpn.data.BackendPaymentMethod(13, "crypto", "Криптовалюта", true))), methodCode = "card")) }
            PlanCheckoutScreen(plan, "Free", false, com.noki.vpn.data.BillingCycle.YEARLY, AppLanguage.RU, {}, null, false,
                470f / 370f, Modifier.padding(horizontal = 21.dp).width(470.dp).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 24.dp),
                payment, { appliedPromo = it }, {}, { payment = payment.copy(methodCode = it) }, {}, {}, {}, {})
        }.use {
            val heading = compose.onNodeWithText("Текущий тариф").fetchSemanticsNode().boundsInRoot
            assertTrue("Plan transition must be at the top", heading.top < 320f)
            saveImage("checkout-top-wide")
            compose.onNodeWithText("5100 ₽").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("-15%").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("NOKI20")
            val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            val apply = compose.onNodeWithText("Применить").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Promo action stays inside the input row", input.center.y, apply.center.y, 4f)
            compose.onNodeWithText("Применить").performClick()
            compose.waitUntil(2000) { appliedPromo == "NOKI20" }
            saveImage("checkout-promo-wide")
            compose.onNodeWithText("Карта").performScrollTo().assertIsSelected()
            compose.onNodeWithText("СБП").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("Карта").assertIsNotSelected()
            compose.onNodeWithText("Криптовалюта").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("СБП").assertIsNotSelected()
            compose.onNodeWithText("Оплатить", substring = true).performScrollTo().assertIsDisplayed()
            saveImage("checkout-methods-wide")
        }
    }

    private fun checkSettingsWidth(title: String, imageName: String, content: @Composable () -> Unit) = render(content).use {
        val header = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Settings header must match the wide content column", header.width > root.width * 0.8f)
        saveImage(imageName)
    }

    @Test
    @Config(qualifiers = "w512dp-h800dp-xxhdpi")
    fun wideShortHomeKeepsConnectionControlsReachable() = checkShortHome("home-short-512")

    @Test
    @Config(qualifiers = "w480dp-h800dp-xxhdpi")
    fun mediumShortHomeKeepsConnectionControlsReachable() = checkShortHome("home-short-480")

    private fun checkShortHome(imageName: String) = renderHome().use {
        compose.onNodeWithText("Не подключено").performScrollTo().assertIsDisplayed()
        saveImage(imageName)
    }

    private fun renderHome(): AutoCloseable {
        val viewModel = layoutViewModel()
        return render {
            HomeScreen(
                state = AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
                viewModel = viewModel, onConnectClicked = {}, onDisconnectClicked = {},
                sharedBackdrop = rememberLayerBackdrop(), liveGlassEnabled = false,
                showBackground = false,
                // The shared navigation's RuntimeShader requires hardware rendering.
                showBottomNavigation = false,
            )
        }
    }

    @Test
    fun currentDeviceUsesAccessiblePencil() {
        val viewModel = layoutViewModel()
        render {
            DevicesScreen(
                state = AppUiState(
                    personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
                    devices = listOf(
                        DeviceSession("current", "Samsung SM-G996B", "Android", true, true),
                        DeviceSession("fixture", "Noki — тестовое устройство", "Android", false, false),
                    ),
                ),
                viewModel = viewModel, sharedBackdrop = rememberLayerBackdrop(),
                showBackground = false, liveGlassEnabled = false,
            )
        }.use {
            compose.onNodeWithContentDescription("Переименовать").assertIsDisplayed()
            compose.onNodeWithText("Переименовать").assertDoesNotExist()
            val heading = compose.onNodeWithText("Текущее устройство").fetchSemanticsNode().boundsInRoot
            val card = compose.onNode(hasText("Samsung SM-G996B") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val removeOthers = compose.onNode(
                hasText("Удалить все устройства кроме текущего") and hasClickAction(),
            ).fetchSemanticsNode().boundsInRoot
            val otherDevices = compose.onNodeWithText("Другие устройства").fetchSemanticsNode().boundsInRoot
            assertTrue("Current-device heading must be outside and above the card", heading.bottom < card.top)
            assertEquals(
                "Current-device content uses equal gaps",
                card.top - heading.bottom, removeOthers.top - card.bottom, 2f,
            )
            assertTrue(
                "Other devices needs a separate group gap",
                otherDevices.top - removeOthers.bottom >= removeOthers.height * 0.40f,
            )
            saveImage("devices-wide")
        }
    }

    private fun layoutViewModel() = MainViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle()).also {
        // Layout uses the supplied state; keep account bootstrap/network work out of this UI fixture.
        it.viewModelScope.cancel()
    }

    @Test
    fun tallProfileStartsAtTop() = render {
        AccountScreen(
            state = AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
            sharedBackdrop = null, liveGlassEnabled = false,
            onPersonalizationClicked = {}, onPlansClicked = {}, onDevicesClicked = {}, onSupportClicked = {},
            onSecurityClicked = {}, onNotificationsClicked = {}, onNotificationDeleted = {},
            onPaymentHistoryClicked = {}, onApplyPromoCode = {}, onClearPromoCode = {},
            onDeleteAccountClicked = {}, onAccessDenied = {}, onDismissDialog = {}, onConfirmDialog = {},
        )
    }.use {
        val header = compose.onNodeWithText("Профиль").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Profile must begin in the top tenth of the screen", header.top < root.height / 10)
        compose.onNodeWithText("Поддержка").performScrollTo()
        val devices = compose.onNodeWithText("Устройства").fetchSemanticsNode().boundsInRoot
        val support = compose.onNodeWithText("Поддержка").fetchSemanticsNode().boundsInRoot
        assertTrue("Devices must precede support", devices.bottom < support.top)
        saveImage("profile-wide")
    }

    @Test
    fun deviceActionsHaveEqualBoundsAndRenameRemainsClickable() {
        var renamed = false
        render {
            DeleteDeviceDialog(
                device = DeviceSession("fixture", "Noki — тестовое устройство", "Android", false, false),
                language = AppLanguage.RU, scale = 470f / 370f,
                backdrop = null, liveGlassEnabled = false,
                onDismiss = {}, onFullAccessChanged = { _, _ -> },
                onRename = { renamed = true }, onConfirm = {},
            )
        }.use {
            val rename = compose.onNode(hasText("Переименовать") and hasClickAction())
            val delete = compose.onNode(hasText("Удалить устройство") and hasClickAction())
            val renameBounds = rename.fetchSemanticsNode().boundsInRoot
            val deleteBounds = delete.fetchSemanticsNode().boundsInRoot
            assertEquals(deleteBounds.width, renameBounds.width, 1f)
            assertEquals(deleteBounds.height, renameBounds.height, 1f)
            assertEquals(deleteBounds.left, renameBounds.left, 1f)
            assertTrue("Device actions need a distinct gap", deleteBounds.top - renameBounds.bottom >= renameBounds.height * 0.30f)
            saveImage("device-dialog-wide")
            rename.performClick()
            assertTrue(renamed)
        }
    }

    private fun render(content: @Composable () -> Unit): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()
        activity.setContent {
            NokiTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF051018))) { content() }
            }
        }
        compose.waitForIdle()
        return AutoCloseable { controller.pause().stop().destroy() }
    }

    private fun saveImage(name: String, includeDialogs: Boolean = false) {
        val directory = File("build-compose-2026/reports/ui-layout").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            compose.runOnIdle {
                val view = if (includeDialogs) {
                    org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
                        activity.windowManager,
                    ).views.last()
                } else activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
        }
    }
}
