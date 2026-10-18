package ir.jirjirak.sms

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.jirjirak.sms.ui.App
import ir.jirjirak.sms.ui.JirjirakTheme
import ir.jirjirak.sms.ui.SummaryCache
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The redesign's flows on a real device, against [FakeBot] on localhost: pairing, the manage key,
 * a decision whose answer never came, a rejection in two steps, a receipt already decided, unpairing
 * with a queue, a health test on an older bot — and the same screens right to left, at twice the font
 * size and narrow, with what TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
class RedesignFlowsTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = Store(context)
    private lateinit var bot: FakeBot
    private var receiptState = "pending"

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        store.unpair()
        store.clearEvents()
        store.senders = emptyList()
        store.theme = "light"
        // No update check from a test: it would reach GitHub.
        store.lastUpdateCheck = System.currentTimeMillis()
        SummaryCache.clear()
        receiptState = "pending"
        bot = FakeBot { method, path, _ ->
            when {
                path == "/ping" -> FakeBot.ping()
                path.startsWith("/receipts/summary") -> FakeBot.summary()
                path.startsWith("/receipts/history") -> FakeBot.history(FakeBot.receipt(7, receiptState))
                path == "/receipts/reasons" -> FakeBot.reasons()
                path == "/receipts" -> 200 to """{"receipts":[]}"""
                path == "/receipts/7" && method == "GET" -> FakeBot.detail(7, receiptState)
                else -> 404 to "{}"
            }
        }
    }

    @After fun tearDown() {
        ir.jirjirak.sms.ui.States.pollMs = 25_000L
        bot.close()
        store.unpair()
        store.clearEvents()
    }

    private fun paired(withKey: Boolean = true) {
        store.endpoint = bot.endpoint
        store.senders = listOf("9999")
        store.lastPing = System.currentTimeMillis()
        store.shopInfo = JSONObject(FakeBot.ping().second)
        if (withKey) store.manageKey = "K".repeat(32)
    }

    private fun launch(tab: Int = MainActivity.TAB_HOME, fontScale: Float? = null, widthDp: Int? = null) {
        rule.setContent {
            JirjirakTheme {
                val content = @androidx.compose.runtime.Composable { App(0, tab, 0, 0) }
                val sized = @androidx.compose.runtime.Composable {
                    if (widthDp != null) Box(Modifier.requiredWidth(widthDp.dp).fillMaxHeight()) { content() } else content()
                }
                if (fontScale != null) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { sized() }
                } else sized()
            }
        }
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().performClick()

    // ── pairing ─────────────────────────────────────────────────────────

    @Test fun manageCodePastedAsPairingCode_isNamedAndNothingIsSaved() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("jirjirak-manage:" + "A".repeat(32))
        tap("بررسی و اتصال")
        waitFor("این کد اتصال نیست")
        rule.onNodeWithText("کد مدیریت رسیدها", substring = true).assertIsDisplayed()
        assertNull(store.endpoint)
    }

    @Test fun unreachableBot_staysUnpaired() {
        launch()
        rule.onNode(hasSetTextAction()).performTextInput("jirjirak-sms:https://127.0.0.1:1/api/bank-sms/" + "a".repeat(24))
        tap("بررسی و اتصال")
        waitFor("ارتباط برقرار نشد")
        rule.onNodeWithText("تغییری ذخیره نشده است").assertIsDisplayed()
        assertNull(store.endpoint)
    }

    @Test fun pairingCode_isMaskedUntilAskedAndHasNoKeyboardLearning() {
        launch()
        val code = "jirjirak-sms:https://bot.example/api/bank-sms/" + "b".repeat(24)
        rule.onNode(hasSetTextAction()).performTextInput(code)
        // The field's text, as an accessibility service or a screenshot sees it, is masked.
        rule.onNode(hasSetTextAction()).assert(SemanticsMatcher("masked") { node ->
            node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text?.contains("bank-sms") != true
        })
        rule.onNodeWithContentDescription("نمایش موقت کد").assertHasClickAction()
    }

    // ── the manage key: kept only after the bot accepts it ──────────────

    @Test fun manageKey_refused401_isNotKept() {
        paired(withKey = false)
        bot.handler = { _, path, _ -> if (path == "/receipts") 401 to "{}" else FakeBot.ping() }
        launch(MainActivity.TAB_RECEIPTS)
        rule.onNode(hasSetTextAction()).performTextInput("jirjirak-manage:" + "Z".repeat(32))
        tap("بررسی دسترسی")
        waitFor("کد مدیریت باطل شده است")
        assertNull(store.manageKey)
    }

    @Test fun manageKey_forbidden403_namesTheSubscription() {
        paired(withKey = false)
        bot.handler = { _, path, _ -> if (path == "/receipts") 403 to "{}" else FakeBot.ping() }
        launch(MainActivity.TAB_RECEIPTS)
        rule.onNode(hasSetTextAction()).performTextInput("jirjirak-manage:" + "Z".repeat(32))
        tap("بررسی دسترسی")
        waitFor("مدیریت رسیدها در دسترس نیست")
        rule.onAllNodesWithText("اشتراک", substring = true)[0].assertIsDisplayed()
        assertNull(store.manageKey)
    }

    @Test fun manageKey_accepted_isKept() {
        paired(withKey = false)
        launch(MainActivity.TAB_RECEIPTS)
        rule.onNode(hasSetTextAction()).performTextInput("jirjirak-manage:" + "Z".repeat(32))
        tap("بررسی دسترسی")
        rule.waitUntil(10_000) { store.manageKey != null }
        assertEquals("Z".repeat(32), store.manageKey)
    }

    // ── deciding a receipt ──────────────────────────────────────────────

    private fun openReceipt() {
        launch(MainActivity.TAB_RECEIPTS)
        waitFor("مشتری آزمون")
        rule.onAllNodesWithText("مشتری آزمون")[0].performClick()
        waitFor("تأیید پرداخت")
    }

    @Test fun approve_withNoReply_isUnknown_thenTheBotsStateIsRead() {
        paired()
        val base = bot.handler
        bot.handler = { m, p, b -> if (p == "/receipts/7/approve") null else base(m, p, b) }
        openReceipt()
        tap("تأیید پرداخت")
        // The confirmation names the amount and the target.
        rule.onNodeWithText("۳۲۰٬۰۰۰ تومان برای مشتری آزمون", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("تأیید پرداخت").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor("پاسخ نهایی دریافت نشد", 20_000)
        // No approve or reject button while the outcome is unknown: no blind retry.
        assertTrue(rule.onAllNodesWithText("رد رسید").fetchSemanticsNodes().isEmpty())
        assertEquals(1, bot.requests.count { it.path == "/receipts/7/approve" })
        receiptState = "approved"
        tap("بررسی وضعیت رسید")
        waitFor("پرداخت تأیید شد")
        assertEquals(1, bot.requests.count { it.path == "/receipts/7/approve" })
    }

    @Test fun reject_needsAReasonAndThenItsOwnConfirmation() {
        paired()
        val base = bot.handler
        bot.handler = { m, p, b -> if (p == "/receipts/7/reject") { receiptState = "rejected"; 200 to "{}" } else base(m, p, b) }
        openReceipt()
        tap("رد رسید")
        waitFor("دلیل رد رسید")
        rule.onNodeWithText("ادامه").assertIsNotEnabled()
        rule.onNodeWithText("مبلغ واریز با سفارش مغایرت دارد").performClick()
        rule.onNodeWithText("ادامه").assertIsEnabled().performClick()
        waitFor("رسید رد شود؟")
        rule.onNodeWithText("دلیل انتخاب‌شده: «مبلغ واریز با سفارش مغایرت دارد»", substring = true).assertIsDisplayed()
        assertTrue(bot.requests.none { it.path == "/receipts/7/reject" })
        rule.onNodeWithText("رد رسید و ارسال دلیل").performClick()
        waitFor("رسید رد شده است")
        assertEquals("amount", JSONObject(bot.requests.first { it.path == "/receipts/7/reject" }.body).getString("reason"))
    }

    @Test fun approve_alreadyDecided409_readsTheReceiptAgain() {
        paired()
        val base = bot.handler
        bot.handler = { m, p, b -> if (p == "/receipts/7/approve") { receiptState = "approved"; 409 to "{}" } else base(m, p, b) }
        openReceipt()
        tap("تأیید پرداخت")
        rule.onAllNodesWithText("تأیید پرداخت").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor("این رسید قبلاً تعیین‌تکلیف شده")
        rule.onNodeWithText("این رسید دیگر منتظر تصمیم نیست", substring = true).performScrollTo().assertIsDisplayed()
    }

    // ── the bank's number: typed by hand, the inbox never read ──────────

    @Test fun senders_manualAdd_isTheOnlyWayAndNeedsAConfirmation() {
        paired()
        store.senders = emptyList()
        launch()
        tap("افزودن شمارهٔ بانک")
        waitFor("هنوز شمارهٔ بانکی ندارید")
        // No inbox picker on the list: typing the number is the only way in.
        assertTrue(rule.onAllNodesWithText("انتخاب از پیامک‌ها", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("پیدا کردن خودکار", substring = true).fetchSemanticsNodes().isEmpty())
        tap("افزودن شمارهٔ بانک")
        waitFor("شماره را از کجا پیدا کنم؟")
        rule.onNode(hasSetTextAction()).performTextInput("+98200")
        tap("بررسی فرستنده")
        waitFor("این فرستنده مجاز شود؟")
        assertTrue("nothing is added before the confirmation", store.senders.isEmpty())
        rule.onNodeWithText("بله، مجاز شود").performClick()
        rule.waitUntil(5_000) { store.senders.isNotEmpty() }
        assertEquals(listOf("+98200"), store.senders)
        // Back on the list, with the add button still there for the next number.
        waitFor("۱ فرستنده مجاز است")
        rule.onNodeWithText("افزودن شماره").assertIsDisplayed()
    }

    @Test fun senders_manualAdd_refusesASenderAlreadyAllowed() {
        paired()
        launch()
        tap("فرستندهٔ مجاز")
        waitFor("شماره‌های مجاز")
        tap("افزودن شماره")
        rule.onNode(hasSetTextAction()).performTextInput("۹۹۹۹")
        tap("بررسی فرستنده")
        waitFor("این فرستنده از قبل مجاز است.")
        assertEquals(listOf("9999"), store.senders)
    }

    @Test fun theAppDoesNotAskForTheInbox() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val asked = info.requestedPermissions.orEmpty().toList()
        assertTrue(Manifest.permission.RECEIVE_SMS in asked)
        assertFalse("READ_SMS is gone: nothing reads the inbox", Manifest.permission.READ_SMS in asked)
    }

    // ── decided in the bot: the app follows ──────────────────────────────

    private val resumed = androidx.compose.runtime.mutableIntStateOf(0)

    /** A bot that answers «pending» lists honestly: once [receiptState] is decided, receipt 7 is not in them. */
    private fun botFollowsState() {
        val base = bot.handler
        bot.handler = { m, p, b ->
            when {
                p.startsWith("/receipts/history") && p.contains("filter=pending") ->
                    if (receiptState == "pending") FakeBot.history(FakeBot.receipt(7, receiptState)) else FakeBot.history()
                p.startsWith("/receipts/summary") -> FakeBot.summary(needsReview = if (receiptState == "pending") 1 else 0)
                else -> base(m, p, b)
            }
        }
    }

    @Test fun decidedInTheBot_leavesThePendingListOnResume_andTheNoticeGoes() {
        paired()
        botFollowsState()
        store.toldPending = 1
        rule.setContent { JirjirakTheme { App(resumed.intValue, MainActivity.TAB_RECEIPTS, 1, 0) } }
        waitFor("مشتری آزمون")
        // Approved in Telegram meanwhile; the seller comes back to the app.
        receiptState = "approved"
        rule.runOnIdle { resumed.intValue++ }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("مشتری آزمون").fetchSemanticsNodes().isEmpty() }
        waitFor("رسیدی منتظر بررسی نیست")
        // The «۱ رسید منتظر» notification follows the bot's count at once.
        rule.waitUntil(5_000) { store.toldPending == 0 }
    }

    @Test fun decidedInTheBot_whileTheReceiptIsOpen_thePollHidesTheButtons() {
        ir.jirjirak.sms.ui.States.pollMs = 1_000L
        paired()
        openReceipt()
        rule.onNodeWithText("تأیید پرداخت").performScrollTo().assertIsDisplayed()
        receiptState = "approved"
        waitFor("در ربات تعیین‌تکلیف شد")
        assertTrue("no approve on a receipt the bot already decided", rule.onAllNodesWithText("تأیید پرداخت").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("این رسید دیگر منتظر تصمیم نیست", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun decidedInTheBot_theOpenListIsReadAgainByThePoll() {
        ir.jirjirak.sms.ui.States.pollMs = 1_000L
        paired()
        botFollowsState()
        rule.setContent { JirjirakTheme { App(0, MainActivity.TAB_RECEIPTS, 1, 0) } }
        waitFor("مشتری آزمون")
        receiptState = "rejected"
        waitFor("رسیدی منتظر بررسی نیست")
    }

    // ── unpair, health ──────────────────────────────────────────────────

    @Test fun unpair_saysHowManyQueuedSmsAreDeleted() {
        paired()
        repeat(2) { i -> store.enqueue(JSONObject().put("id", "q$i").put("from", "9999").put("message", "واریز $i"), "fp-test-$i-" + System.nanoTime()) }
        launch(MainActivity.TAB_MORE)
        tap("اتصال فروشگاه")
        tap("قطع اتصال از این فروشگاه")
        waitFor("۲ پیامک این فروشگاه هنوز ارسال نشده است")
        rule.onNodeWithText("قطع اتصال و حذف صف").assertIsDisplayed()
        rule.onNodeWithText("حفظ اتصال").performClick()
        assertTrue(store.endpoint != null)
    }

    @Test fun healthTest_onABotWithoutDryRun_isNotCalledHealthy() {
        paired(withKey = false)
        bot.handler = { _, path, _ -> if (path == "/ping") FakeBot.ping(features = emptyList()) else 404 to "{}" }
        store.shopInfo = JSONObject(FakeBot.ping(features = emptyList()).second)
        // The emulator's battery saver would add its own warning; this test is about the dry run.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys deviceidle whitelist +" + context.packageName).close()
        launch()
        tap("تست سلامت")
        waitFor("صف واقعی ممکن است ارسال شود")
        tap("شروع تست")
        waitFor("نیاز به به‌روزرسانی ربات", 20_000)
        rule.onNodeWithText("اتصال سالم؛ آزمون کامل نشده").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("مسیر پیامک سالم است").fetchSemanticsNodes().isEmpty())
        // Nothing but pings reached the bot: no sample SMS on the real ingest road.
        assertTrue(bot.requests.all { it.path == "/ping" })
    }

    // ── RTL, font scale, narrow screens, TalkBack ───────────────────────

    @Test fun rtl_homeTabIsRightmost() {
        paired()
        launch()
        waitFor("خانه")
        val home = rule.onAllNodesWithText("خانه")[0].fetchSemanticsNode().boundsInRoot
        val more = rule.onAllNodesWithText("بیشتر")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("خانه must sit right of بیشتر", home.left > more.left)
    }

    @Test fun twiceTheFontOnA320dpScreen_keepsTargetsAndText() {
        paired()
        launch(fontScale = 2f, widthDp = 320)
        waitFor("ارسال و تأیید خودکار فعال است")
        rule.onNodeWithText("آماده‌سازی گوشی").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("آماده‌سازی گوشی") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("امنیت و دسترسی‌ها").assertHeightIsAtLeast(48.dp)
    }

    @Test fun pairing320dp_twiceTheFont() = narrowPairing(320)
    @Test fun pairing360dp_twiceTheFont() = narrowPairing(360)
    @Test fun pairing390dp_twiceTheFont() = narrowPairing(390)
    @Test fun pairing412dp_twiceTheFont() = narrowPairing(412)

    private fun narrowPairing(w: Int) {
        launch(fontScale = 2f, widthDp = w)
        rule.onNodeWithText("چسباندن کد اتصال").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("بررسی و اتصال").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun width360_receipts() = narrowReceipts(360)
    @Test fun width390_receipts() = narrowReceipts(390)
    @Test fun width412_receipts() = narrowReceipts(412)

    private fun narrowReceipts(w: Int) {
        paired()
        launch(MainActivity.TAB_RECEIPTS, fontScale = 2f, widthDp = w)
        waitFor("مشتری آزمون")
        rule.onAllNodesWithText("نیازمند بررسی", substring = true)[0].assertIsDisplayed()
        rule.onAllNodesWithText("مشتری آزمون")[0].performScrollTo().assertIsDisplayed()
    }

    @Test fun talkback_rowsAreOneNodeAndDecorativeArtIsSilent() {
        paired()
        launch()
        waitFor("سلامت ارسال")
        // A row is ONE focusable, clickable node that reads its title and its state together.
        rule.onNode(hasText("آماده‌سازی گوشی") and hasClickAction()).performScrollTo().assertIsDisplayed()
        // Icon-only controls have a spoken name.
        rule.onNodeWithContentDescription("امنیت و دسترسی‌ها").assertHasClickAction()
        // No node speaks an image file's name; illustrations carry no description at all.
        val described = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
        assertFalse(described.any { n -> n.config[SemanticsProperties.ContentDescription].any { it.contains("ill") || it.contains("jrs_") } })
    }
}
