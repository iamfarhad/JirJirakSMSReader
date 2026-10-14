# جیرجیرک پیامک — JirjirakSMS

The Android companion of the Jirjirak bot: it forwards the shop's **bank SMS** to the bot, which
matches each deposit to a card-to-card invoice and approves it automatically.

It replaces the generic SMS-forwarder setup (MacroDroid and friends), which almost no seller managed to
configure: pairing here is **copy a code in the bot → paste it in the app**.

## What leaves the phone — and what never does

- **Nothing**, until the seller adds at least one sender to «شماره‌های مجاز بانک». The list is the seller's
  own; the app only *suggests* senders whose SMS looked like a bank's, and never adds one by itself.
- Only SMS from those senders are forwarded, to the one shop the phone is paired with.
- **One-time passwords and verification codes never leave the phone**, even from an allowed sender
  (رمز / پویا / کد تأیید / OTP…).
- The app only RECEIVES new SMS (`RECEIVE_SMS`). It never reads the inbox.

The rules live in `app/src/main/java/ir/jirjirak/sms/Rules.kt` and are pinned by `RulesTest`.

## How it works

| Part | Role |
|---|---|
| `SmsReceiver` | Hears each new SMS (works with the app closed), applies `Rules`, queues it |
| `Store` | Pairing, allowed senders, suggestions, the offline queue, the delivery log |
| `Net` | `POST {ingest}` per SMS; `POST {ingest}/ping` heartbeat with version, queue size, senders |
| `SyncJob` | Every 15 min (and after a failed send): flush the queue, ping the bot |
| `BootReceiver` | Re-arms the heartbeat after a reboot or an app update |

The pairing code the bot shows is `jirjirak-sms:https://<server>/api/bank-sms/<secret>`, so one APK
works for every shop on every server.

## Building

CI (`.github/workflows/build-apk.yml`) runs the unit tests and builds on every push; a `v*` tag
publishes `JirjirakSMS.apk` to Releases — the bot links
`releases/latest/download/JirjirakSMS.apk`.

### Release signing (once)

The release key must never be committed. Create it once, keep a backup (an APK signed with another key
cannot update the installed app), and store it as repository secrets:

```bash
keytool -genkeypair -v -keystore jirjirak-release.jks -alias jirjirak -keyalg RSA -keysize 4096 -validity 36500
base64 -i jirjirak-release.jks | gh secret set KEYSTORE_BASE64
gh secret set STORE_PASSWORD
gh secret set KEY_PASSWORD
gh secret set KEY_ALIAS --body jirjirak
```

Without these secrets CI builds only the debug APK and publishes nothing.

### Locally

JDK 17 or 21 and the Android SDK (platform 36):

```bash
./gradlew testDebugUnitTest assembleDebug
```
