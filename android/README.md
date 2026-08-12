# حصن · Hisn

<div dir="rtl">

**حصن** مدير كلمات سرّ يعمل دون اتصال، عربيُّ الواجهة أوّلًا. يقرأ صيغة **KDBX** ويكتبها، فالملف
نفسه يُفتح في KeePassXC على الحاسوب دون تحويل. لا حساب، ولا خادم، ولا تتبّع: كل شيء يبقى في
ملفٍ واحد على جهازك، والمزامنة — إن أردتها — تجري مباشرة بين جهازين على الشبكة المحلية نفسها.

</div>

**Hisn** is an Arabic-first, offline password manager for Android. It reads and writes the
**KDBX** format, so the same file opens in KeePassXC on the desktop. No account, no server, no
telemetry: the vault is one file on your device, and the optional sync runs directly between two
devices on the same local network.

---

## متطلبات البناء · Build requirements

<div dir="rtl">

| المتطلّب | الإصدار |
| --- | --- |
| JDK | 17 (‏`/usr/lib/jvm/java-17-openjdk-amd64`) |
| Android SDK | المنصّة 36 وأدوات البناء 35 فما فوق |
| Gradle | 9.3.1 — يُنزّله الغلاف تلقائيًا |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.20 |

</div>

| Requirement | Version |
| --- | --- |
| JDK | 17 (`/usr/lib/jvm/java-17-openjdk-amd64`) |
| Android SDK | platform 36, build-tools 35 or newer |
| Gradle | 9.3.1 — fetched by the wrapper |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.20 |

`local.properties` must point at the SDK. It is already written for this machine:

```properties
sdk.dir=/home/professor/Android/Sdk
```

`gradle.properties` pins the build JVM with `org.gradle.java.home`. Change that line if your JDK 17
lives somewhere else — Gradle 9 and AGP 8.13 both refuse to run on anything older than 17.

---

## بناء ملف APK · Building the APK

<div dir="rtl">

من داخل مجلد `android/`:

</div>

From inside the `android/` directory:

```bash
# نسخة التصحيح · debug build
./gradlew :app:assembleDebug
# ← app/build/outputs/apk/debug/app-debug.apk

# نسخة الإصدار (موقّعة بمفتاح التصحيح، بلا تصغير) · release build
./gradlew :app:assembleRelease
# ← app/build/outputs/apk/release/app-release.apk
```

<div dir="rtl">

نسخة الإصدار موقَّعة بمفتاح التصحيح (`~/.android/debug.keystore`) والتصغير معطّل فيها، حتى تبقى
قابلة للتثبيت والتصحيح مباشرة. قبل أي نشر عام، استبدل `signingConfigs` في
`app/build.gradle.kts` بمفتاح حقيقي.

</div>

The release variant is signed with the debug key (`~/.android/debug.keystore`) and R8 is off, so
the artifact stays installable and readable in a stack trace. Replace the `signingConfigs` block in
`app/build.gradle.kts` with a real keystore before publishing anywhere.

### التثبيت · Installing

```bash
./gradlew :app:installDebug          # عبر adb على جهاز موصول
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## الاختبارات · Tests

<div dir="rtl">

الاختبارات كلها اختبارات JVM ولا تحتاج جهازًا ولا محاكيًا:

</div>

All tests are plain JVM unit tests — no device, no emulator:

```bash
./gradlew :app:testDebugUnitTest
# التقرير · report: app/build/reports/tests/testDebugUnitTest/index.html
```

| الاختبار · Test | ما يغطّيه · What it covers |
| --- | --- |
| `KdbxRoundTripTest` | كتابة قاعدة بيانات كاملة ثم قراءتها ومطابقة كل حقل — لصيغتَي KDBX 4.1 (Argon2d/AES-256، وChaCha20/Argon2id) وKDBX 3.1 (AES-KDF)، مع رفض كلمة السرّ الخاطئة وملف المفتاح الخاطئ. <br> Full write/read/compare round trip for KDBX 4.1 and 3.1, plus wrong-password and wrong-key-file rejection. |
| `TotpTest` | متجهات RFC 6238 لخوارزميات SHA1 وSHA256 وSHA512، ومتجه Steam Guard، وتحليل روابط `otpauth://` وصيغ `key=value` والصيغة القديمة `[step];[digits]`. <br> RFC 6238 vectors for SHA1/SHA256/SHA512, a Steam Guard vector, and parsing of `otpauth://` URIs, `key=value` strings and the legacy settings pair. |

<div dir="rtl">

اختبارات الجولة الكاملة تستعمل إعدادات اشتقاق مفتاح مصغَّرة عمدًا (ميغابايت واحد وجولتان من
Argon2) — فهي لا تغيّر شيئًا فيما يُكتب إلى الملف، وتختصر زمن الاختبار من دقائق إلى ثوانٍ.

</div>

The round-trip tests deliberately use tiny KDF settings (1 MiB, two Argon2 passes). They change
nothing about what lands in the file and cut the run from minutes to seconds.

---

## بنية المشروع · Project layout

```
android/
├── app/src/main/kotlin/org/hisn/app/
│   ├── kdbx/      قراءة KDBX وكتابته، والدمج، وTOTP · format, merge, TOTP
│   ├── data/      المستودع والتخزين المحمي بالـ Keystore · repository, Keystore-backed storage
│   ├── sync/      المزامنة عبر الشبكة المحلية · local-network sync
│   └── ui/        واجهة Compose · Compose UI (theme, components, screens)
├── app/src/main/res/
│   ├── values-ar/ العربية — لغة التطبيق الأصلية · Arabic, the product language
│   └── values/    الإنجليزية احتياطًا · English fallback
└── app/src/test/kotlin/org/hisn/app/kdbx/   اختبارات JVM · JVM tests
```

<div dir="rtl">

الشيفرة في `src/main/kotlin` وليست في `src/main/java`، وهذا مضبوط في `app/build.gradle.kts`.

</div>

Sources live in `src/main/kotlin`, not `src/main/java`; the source set is configured accordingly in
`app/build.gradle.kts`.

---

## ملاحظات أمنية · Security notes

<div dir="rtl">

- النسخ الاحتياطي معطّل (`allowBackup="false"`)، وقواعد `data_extraction_rules.xml` تمنع رفع
  الخزنة إلى نسخة سحابية أو نقلها في أثناء إعداد جهاز جديد.
- إذن الإنترنت مطلوب للمزامنة المحلية فقط: مقبس مباشر بين جهازين على الشبكة نفسها، لا خادم وسيط.
- إذن الكاميرا يُستعمل حصرًا لقراءة رمز الإقران؛ لا تُحفظ صورة ولا تُرسَل.

</div>

- Backup is off (`allowBackup="false"`) and `data_extraction_rules.xml` refuses both cloud backup
  and device-to-device transfer, so the vault cannot leave the device through the system.
- The INTERNET permission exists only for local-network sync — a direct socket between two devices
  on the same Wi-Fi, with no intermediary.
- The camera is used only to read the pairing QR code. No frame is stored or transmitted.

---

## الترخيص · Licence

<div dir="rtl">

يتبع حصن ترخيص مشروع KeePassXC الذي اشتُقّ منه: رخصة جنو العمومية، الإصدار الثاني أو الثالث.

</div>

Hisn follows the licence of the KeePassXC project it derives from: GNU General Public License,
version 2 or 3.
