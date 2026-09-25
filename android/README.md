# حصن · Hisn

<div dir="rtl">

**حصن** مدير كلمات سرّ يعمل دون اتصال، عربيُّ الواجهة أوّلًا — وهو التطبيق نفسه على منصّاته
الثلاث: أندرويد، وويندوز، ولينكس. يقرأ صيغة **KDBX** المفتوحة ويكتبها، فالملف نفسه يفتحه حصن
على حاسوبك دون تحويل. لا حساب، ولا خادم، ولا تتبّع: كل شيء يبقى في ملفٍ واحد على جهازك،
والمزامنة — إن أردتها — تجري مباشرة بين جهازين على الشبكة المحلية نفسها.

</div>

**Hisn** is an Arabic-first, offline password manager — the same product on all three of its
platforms: Android, Windows and Linux. It reads and writes the open **KDBX** format, so the same
file opens in Hisn on the desktop. No account, no server, no telemetry: the vault is one file on
your device, and the optional sync runs directly between two devices on the same local network.

<div dir="rtl">

**معرّف التطبيق:** ‏`org.amanlabs.hisn`‏ — هوية عائلة أمان الموحّدة (نسخة التصحيح تحمل اللاحقة
`.debug`). أسماء الحزم في الشيفرة تبقى `org.hisn.app`؛ فالـnamespace شأن داخلي لا يراه المستخدم.

</div>

**Application id:** `org.amanlabs.hisn` — the unified Aman-family identity (the debug build
carries a `.debug` suffix). Source packages remain `org.hisn.app`; the namespace is internal.

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
sdk.dir=<your Android SDK path>
```

JAVA_HOME must point to a JDK 17 (gradle.properties no longer pins a machine-specific path).
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

# نسخة الإصدار (موقّعة بمفتاح الإصدار الحقيقي، بلا تصغير) · release build
./gradlew :app:assembleRelease
# ← app/build/outputs/apk/release/app-release.apk
```

<div dir="rtl">

نسخة الإصدار موقَّعة بمفتاح العائلة الحقيقي: يقرأ البناء `keystore/keystore.properties` ويوقّع
بالمخزن `keystore/hisn.p12` (‏PKCS12، الاسم المستعار `hisn`‏). وإن غاب ملف الخصائص فَشِل بناء
الإصدار برسالة صريحة — لا يُوقَّع الإصدار بمفتاح التصحيح أبدًا. مجلد `keystore/` لا يدخل أي
مستودع، وفقدانه يعني تعذّر تحديث التطبيق لمن ثبّته؛ راجع ملف التحذير داخله. التصغير يبقى معطّلًا
عمدًا حتى يبقى أثر التعقّب مقروءًا.

</div>

The release variant is signed with the real family key: the build reads
`keystore/keystore.properties` and signs with `keystore/hisn.p12` (PKCS12, alias `hisn`). If the
properties file is missing, the release build fails with an explicit error — a release is never
silently signed with the debug key. The `keystore/` folder must never enter a repository, and
losing it means installed copies can no longer be updated; see the warning file inside it. R8
stays off on purpose so stack traces remain readable. The debug variant keeps the ordinary debug
keystore (`~/.android/debug.keystore`).

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
| `DesktopCompatTest` | فتح ملف ‏`.kdbx`‏ كتبه KeePassXC نفسه (‏`app/src/test/resources/`‏) وإعادة كتابته دون فقدان. <br> Opens a `.kdbx` written by KeePassXC itself (`app/src/test/resources/`) and rewrites it without loss. |

<div dir="rtl">

اختبارات الجولة الكاملة تستعمل إعدادات اشتقاق مفتاح مصغَّرة عمدًا (ميغابايت واحد وجولتان من
Argon2) — فهي لا تغيّر شيئًا فيما يُكتب إلى الملف، وتختصر زمن الاختبار من دقائق إلى ثوانٍ.

</div>

The round-trip tests deliberately use tiny KDF settings (1 MiB, two Argon2 passes). They change
nothing about what lands in the file and cut the run from minutes to seconds.

---

## بنية المشروع · Project layout

كل ما يخصّ تطبيق أندرويد موجود داخل هذا المجلّد وحده؛ لا شيء منه مبعثر في بقيّة المستودع.
Everything Android lives inside this one directory — nothing is scattered through the rest of the repo.

```
android/                        ← المشروع كاملًا · the entire Android project
├── settings.gradle.kts         إعداد Gradle · Gradle settings
├── build.gradle.kts            إضافات المشروع · root plugins
├── gradle/libs.versions.toml   كتالوج الإصدارات · version catalogue
├── gradlew · gradlew.bat       غلاف Gradle · Gradle wrapper
├── local.properties            مسار SDK (خاص بالجهاز، غير متعقَّب) · SDK path (machine-local, untracked)
├── keystore/                   مفتاح توقيع الإصدار وخصائصه — لا يدخل أي مستودع · release signing key + properties, never committed
├── SYNC-PROTOCOL.md            مواصفة بروتوكول المزامنة · the sync wire spec
├── README.md                   هذا الملف · this file
└── app/
    ├── build.gradle.kts
    ├── src/main/AndroidManifest.xml
    ├── src/main/kotlin/org/hisn/app/
    │   ├── kdbx/      قراءة KDBX وكتابته، والدمج، وTOTP · format, merge, TOTP
    │   ├── data/      المستودع والتخزين المحمي بالـ Keystore · repository, Keystore-backed storage
    │   ├── sync/      المزامنة عبر الشبكة المحلية · local-network sync
    │   └── ui/        واجهة Compose · Compose UI (theme, components, screens)
    ├── src/main/res/
    │   ├── font/      خط Almarai · the Almarai typeface
    │   ├── values-ar/ العربية — لغة التطبيق الأصلية · Arabic, the product language
    │   └── values/    الإنجليزية احتياطًا · English fallback
    └── src/test/
        ├── kotlin/org/hisn/app/{kdbx,sync}/  اختبارات JVM · JVM tests
        └── resources/desktop-kdbx31.kdbx     قاعدة كتبها تطبيق سطح المكتب · a database written by the desktop app
```

<div dir="rtl">

المخرجات (`build/` و`.gradle/` و`app/build/`) يولّدها Gradle ويتجاهلها `android/.gitignore`، فلا حاجة
لأي قاعدة في `.gitignore` الجذري.

</div>

Build outputs (`build/`, `.gradle/`, `app/build/`) are generated by Gradle and ignored by
`android/.gitignore`, so the root `.gitignore` needs no Android rules.

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
- لا إذن للكاميرا أصلًا: الإقران يجري بكتابة رمز قصير يظهر على الجهاز الآخر — لا مسح لرمز QR،
  ولا صلاحية تصوير من الأساس.
- قائمة الأذونات محروسة في البناء نفسه: مهمة Gradle تفحص المانيفست المدموج وتُفشل البناء إن ظهر
  أي إذن خارج الأربعة المعلنة (الإنترنت، حالة الشبكة، القياسات الحيوية، والبصمة لواجهات ‏26–27).

</div>

- Backup is off (`allowBackup="false"`) and `data_extraction_rules.xml` refuses both cloud backup
  and device-to-device transfer, so the vault cannot leave the device through the system.
- The INTERNET permission exists only for local-network sync — a direct socket between two devices
  on the same Wi-Fi, with no intermediary.
- There is no camera permission at all: pairing is done by typing a short code shown on the other
  device — no QR scanning, and no capture capability in the first place.
- The permission list is enforced by the build itself: a Gradle guard task inspects the merged
  manifest and fails the build if anything outside the four declared permissions (INTERNET,
  ACCESS_NETWORK_STATE, USE_BIOMETRIC, and USE_FINGERPRINT for API 26–27) appears.

---

## الترخيص · Licence

<div dir="rtl">

يتبع حصن ترخيص مشروع KeePassXC الذي اشتُقّ منه: رخصة جنو العمومية، الإصدار الثاني أو الثالث.

</div>

Hisn follows the licence of the KeePassXC project it derives from: GNU General Public License,
version 2 or 3.
