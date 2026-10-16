# بازطراحی نسخهٔ ۲ — نگاشت حالات به کد واقعی

این سند می‌گوید هر یک از ۱۰۰ حالت بستهٔ طراحی v2 در سورس کجاست و از چه دادهٔ واقعی ساخته می‌شود.
چهار تب حفظ شده‌اند: خانه، رسیدها، گزارش، بیشتر. مسیرها (route) در `ui/App.kt` با `Nav` نگه داشته می‌شوند؛
وضعیت‌های خالص در `ui/States.kt` هستند و با `PresentationStatesTest` قفل شده‌اند.

## مبنای طراحی در کد

| چیز | کجا |
|---|---|
| توکن‌های رنگ روشن/تاریک | `ui/Theme.kt` → `Brand`؛ همان مقادیر `res/values(-night)/jrs_colors.xml` |
| تایپوگرافی (وزیرمتن ۴۰۰/۵۰۰/۷۰۰، بدون ۶۰۰ مصنوعی) | `ui/Theme.kt` → `Type` و `JrsTypography` |
| ۳۴ آیکون | `res/drawable/jrs_ic_*.xml` → `ui/Design.kt` → `Ico` |
| ۲۶ تصویرسازی | `res/drawable-nodpi/jrs_ill_*.png` → `Art` |
| نشان/آیکون اپ | `res/drawable/ic_launcher_{foreground,monochrome,background}.xml` و `ic_notification.xml` از `jrs_mascot` |
| کامپوننت‌ها (Hero، AppCard، ListRow، Notice، دکمه‌ها، SecureField، Chip، ConfirmDialog، MetricTile…) | `ui/Design.kt` |

## نگاشت ۱۰۰ حالت

«route» یعنی مقدار `nav.home(...)` / `nav.more(...)` یا Route داخلی تب رسیدها. «منبع» یعنی دادهٔ واقعی که حالت را می‌سازد.

### اتصال (بدون تب)

| حالت | route / state | منبع |
|---|---|---|
| 01 pair-empty | `PairScreen` · `States.Pair.EMPTY` | `store.endpoint == null` |
| 02 pair-loading | `States.Pair.CHECKING` | ping نامزد در جریان |
| 03 pair-invalid | `States.Pair.INVALID` | `Rules.parseCode == null` (کد مدیریت جداگانه نام برده می‌شود) |
| 04 pair-offline | `States.Pair.OFFLINE` | `Net.Ping.UNREACHABLE` |
| 66 pair-revoked | `States.Pair.REVOKED` | `Net.Ping.REVOKED` (۴۰۳/۴۰۴) |

### خانه

| حالت | route / state | منبع |
|---|---|---|
| 05 setup | `home("setup")` | `States.setup(...)`: اجازهٔ RECEIVE_SMS، فرستنده‌ها، اعلان، باتری، Autostart |
| 06 sms-permission | `home("sms_permission")` | دیالوگ خود اندروید؛ بعدش ۰۸ یا ۰۷ |
| 07 restricted | `home("restricted")` | API ≥ ۳۳ و `shouldShowRequestPermissionRationale == false` |
| 08 setup-done | `home("setup_done")` | اجازه داده شد، فرستنده هنوز صفر |
| 55 notify-denied | `home("notify_denied")` | POST_NOTIFICATIONS رد شده؛ ارسال پیامک مستقل است |
| 09 senders-empty | `home("senders")` | `store.senders.isEmpty()` |
| 10 senders-pick | `home("pick")` | `Inbox.group(...)` روی گوشی |
| 11 senders-confirm | `ConfirmSenderDialog` | شناسهٔ کامل با BiDi ایزوله؛ متن فقط با درخواست |
| 12 senders-active | `home("senders")` | `store.senders` |
| 60 sender-remove | `ConfirmDialog` در `SendersScreen` | `store.removeSender` |
| 67 sender-manual | `home("manual")` | `Rules.normalize` / `Rules.isAllowed` |
| 68 inbox-loading | `home("pick")`، `senders == null` | خواندن صندوق |
| 69 inbox-empty | `home("pick")`، فهرست خالی | — |
| 70 read-denied | `home("pick")`، READ_SMS رد شده | — |
| 13 home-active | `Dashboard` · `HomeHero.AUTO_ON` | `summary.autoOn == true` از `/receipts/summary` |
| 14 home-no-key | `HomeHero.FORWARDING` + `Stats.NO_KEY` | `store.manageKey == null` |
| 15 home-loading | `Stats.LOADING` | `SummaryCache.value == null` و بدون خطا |
| 16 home-no-sms | `HomeHero.NO_SMS` | RECEIVE_SMS داده نشده |
| 17 home-offline | `HomeHero.OFFLINE` | `States.online(lastPing)` false |
| 18 home-queue | `HomeHero.QUEUE` → `home("queue")` | `store.queueSize() > 0` |
| 19 home-revoked | `HomeHero.REVOKED` | `store.lastError == "revoked"` |
| 52 home-autooff | `HomeHero.AUTO_OFF` | `summary.autoOn == false` |
| 71 home-stale | `Stats.STALE` | آمار قدیمی‌تر از ۱۵ دقیقه یا تازه‌سازی ناموفق؛ برچسب «آخرین آمار» |
| 72 stats-error | `Stats.ERROR` | بدون هیچ آمار و درخواست ناموفق؛ «—» نه صفر |
| 50 dark | `Store.theme` | همان صفحه‌ها با توکن‌های شب |
| 53/54 sub-expired/grace | `SubscriptionNotice` → `more("subscription")` | `shopInfo.subscription.state` |
| 20 health-run | `home("health")`، `running` | `HealthCheck.run` (اثر flush صف قبل از اجرا گفته می‌شود) |
| 21 health-good | `HealthCheck.Outcome.GOOD` | dry_run اجرا و خوانده شد |
| 22 health-bad | `Outcome.BAD/WARN` | هر گام BAD/WARN |
| 89 health-unavailable | `Outcome.PARTIAL` | ربات `dry_run` را اعلام نکرده؛ نمونه به مسیر واقعی نمی‌رود |
| 73 queue-sending | `home("queue")`، `sending` | `Net.flush` در جریان |
| 74 queue-rate-limit | `States.QueueRow.THROTTLED` | رویداد retry با ۴۲۹ |
| 75 send-permanent | `QueueScreen` → «ارسال‌های ناموفق» | رویدادهای `failed` |
| 65 queue-storage | `QueueScreen` + ردیف در `ActionsNeeded` | رویداد «پیامک بانک ذخیره نشد» |

### رسیدها

| حالت | route / state | منبع |
|---|---|---|
| 23 manage-key | `ManageKeyScreen` | `store.manageKey == null`؛ کد قبل از ذخیره با `Receipts.verify` (GET /receipts) تأیید می‌شود |
| 24 manage-revoked | `KeyCheck.WRONG` یا `manageKeyRevoked` | ۴۰۱ |
| 61 manage-forbidden | `KeyCheck.FORBIDDEN` | ۴۰۳ → اشتراک، نه کد |
| 25 receipts-all / 26 pending / 77 approved / 34 reconcile-list | `Route.List` + فیلتر | `/receipts/history?filter=` |
| 27 receipts-empty | فهرست خالی با پاسخ موفق | `EmptyState` (از خطا جداست) |
| 28 receipts-error | `error != null && items == null` | درخواست ناموفق |
| 76 receipts-loading | `items == null` | — |
| 29 receipt-detail / 79 detail-loading | `Route.Receipt(id)` | `/receipts/{id}` |
| 30 receipt-confirm | `Phase.ConfirmApprove` | مبلغ و مشتری/سفارش در متن تأیید |
| 31 receipt-reject | `Phase.ChooseReason` | `/receipts/reasons`؛ «ادامه» تا انتخاب دلیل غیرفعال |
| 84 reject-confirm | `Phase.ConfirmReject` | تأیید جداگانه با دلیل |
| 82 approve-saving | `Phase.Saving` / `Phase.Confirming` | دکمه‌ها غیرفعال؛ موفقیت فقط بعد از خواندن دوبارهٔ رسید |
| 83 approve-unknown | `Phase.Unknown` | `Receipts.Decision.Unknown` (بدون پاسخ / ۵xx) → فقط «بررسی وضعیت» |
| 62 already-decided | `Phase.Already` | ۴۰۹ → تازه‌سازی |
| 32 receipt-success | `decided && state == approved` | وضعیت سرور |
| 78 receipt-rejected | `state == rejected` | وضعیت سرور |
| 33 receipt-image / 80 image-loading / 81 image-preview | `showImage` / `imageFailed` | `/receipts/{id}/image`؛ با رفتن به پس‌زمینه بسته می‌شود (`Privacy.epoch`) |
| 35 reconcile-assign / 85 assign-empty | `Route.Assign` · `AssignPhase.Picking` | `/receipts/candidates` |
| 97 assign-confirm | `AssignPhase.Confirm` | مبلغ واریز و سفارش و اختلاف در متن |
| 36 reconcile-diff | `AssignPhase.Differs` | `Receipts.Assign.AmountDiffers` (۴۲۲) |
| 98 assign-saving | `AssignPhase.Saving` | — |
| 99 assign-success | `AssignPhase.Done` | پاسخ موفق |
| (۸۳ برای واریز) | `AssignPhase.Unknown` | `Receipts.Assign.Unknown` |
| 100 discard-confirm | `ConfirmDialog` در `DepositCard` | `/deposits/{id}/ignore` با `Decision` |
| 37 teach / 86 teach-incomplete | `Route.Teach` | `sms.numbers`؛ اعداد منفی قابل انتخاب نیستند (`teachableAmount`) |
| 87 teach-saving / 88 teach-error / 38 teach-success | `busy` / `error` / `done` | `/receipts/formats` |

### گزارش

| حالت | route / state | منبع |
|---|---|---|
| 39 report | `ReportTab` | `/receipts/summary?range=` + `store.events()` |
| 40 report-empty | `events.isEmpty()` | — |
| 41 report-filter | فیلتر «مشکل‌ها» | `status in warn/failed/retry` |
| 57 report-clear | `ConfirmDialog` | `store.clearEvents()` (صف دست نمی‌خورد) |
| 64 otp-ignored | ردیف `ignored` + اطلاعیهٔ «پیامک‌های رمز» | بدون متن و بدون fingerprint |

### بیشتر

| حالت | route / state | منبع |
|---|---|---|
| 42 more | `MoreHub` | `SummaryCache`، `Updates.available`، `shopInfo` |
| 43 more-security / 93 manage-clear | `more("security")` | وجود کدها، نه خودشان |
| 44 more-recovery / 56 recovery-denied / 90–92 | `more("recovery")` | `Recovery.scan`؛ نتیجه «در صف» است نه «ارسال شد» |
| 45 more-notify | `more("notify")` | `store.notify*` |
| 46 more-formats / 59 formats-empty / 94 format-delete | `more("formats")` | `/receipts/formats` |
| 47 more-update / 58 update-error / 95 update-checking / 96 update-current | `more("update")` | `Updates.check`؛ لینک در مرورگر، بدون نصب |
| 48 more-appearance / 51 widget / 63 widget-unavailable | `more("appearance")` | `StatusWidget.requestPin` |
| 49 more-disconnect | `more("connection")` | `States.unpairText(queueSize)` |
| 53/54 | `more("subscription")` | `shopInfo.subscription` |

## تغییرهای رفتاری (جدا از UI)

- `Receipts.Decision` / `Receipts.Assign.Unknown`: بدون پاسخ یا ۵xx بعد از ارسال، نتیجه «نامشخص» است؛ اپ وضعیت را دوباره می‌خواند، retry کور نمی‌کند.
- `Receipts.verify` / `KeyCheck`: کد مدیریت قبل از ذخیره با ربات تأیید می‌شود؛ ۴۰۱ کد، ۴۰۳ اشتراک، ۴۰۴ اتصال/نسخه.
- `HealthCheck.outcome`: روی ربات بدون `dry_run` نتیجه «ناقص» است، نه «سالم».
- `Net.describe`: قطع ارتباط وسط کار به فارسی گفته می‌شود، نه نام کلاس جاوا.
- `MainActivity`: تصویر recent-apps غیرفعال (API ≥ ۳۳)؛ با رفتن به پس‌زمینه کد نمایش‌داده‌شده و عکس رسید بسته می‌شوند.

هیچ تغییری در ربات (بک‌اند) لازم نیست؛ همهٔ مسیرها از قبل در `routes/api.php` ربات هستند.

## تست و Build

```bash
./gradlew testDebugUnitTest                  # JVM: Rules، Recovery/Health، Receipts/Alerts، Report/Updates، SemVer/Inbox، PresentationStates
./gradlew connectedDebugAndroidTest          # روی امولاتور/گوشی: جریان‌ها، RTL، fontScale 2، عرض ۳۲۰–۴۱۲dp، semantics
./gradlew assembleDebug
```

تست‌های UI با یک ربات ساختگی روی `127.0.0.1` داخل خود پروسهٔ تست اجرا می‌شوند (`androidTest/.../FakeBot.kt`)؛ به هیچ سرویس واقعی وصل نمی‌شوند.
