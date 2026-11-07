# UnifiedPush Distributor Contract — as implemented by connector 3.3.5

Implementation spec for building a UnifiedPush **distributor** (the
ntfy-side role) that interoperates with apps using
`org.unifiedpush.android:connector` **3.3.5**. Research only; no code
was changed.

## Sources and method

| Source | What was taken from it |
|---|---|
| `~/workspace/web2apk/gecko-push/deps/up/connector-3.3.5.aar` (168,684 bytes; POM `org.unifiedpush.android:connector:3.3.5`, deps kotlin-stdlib 2.2.10 compile, `com.google.crypto.tink:tink:1.23.0` runtime) | Unzipped. `classes.jar` disassembled with `javap -p -c -constants` (JDK in `~/workspace/web2apk/tools/jdk`); `AndroidManifest.xml` read as text. The pre-extracted copy in `~/workspace/web2apk/gecko-push/deps/up/x/` (`classes.jar`, `AndroidManifest.xml`, `R.txt`, empty `proguard.txt`) is byte-equivalent in content and was cross-checked — it adds nothing beyond the AAR. |
| `~/workspace/web2apk/gecko-push/app/src/main/java/app/webgen/pushspike/PushBridge.kt` | Working app-side usage: `UnifiedPush.getDistributors`, `getSavedDistributor`, `tryUseCurrentOrDefaultDistributor`, `register(context, instance, messageForDistributor, vapid)`, `unregister`. |
| `~/workspace/web2apk/gecko-push/app/src/main/java/app/webgen/pushspike/PushReceiver.kt` | App-side `MessagingReceiver` subclass surface. |
| `~/workspace/web2apk/gecko-push/app/src/main/java/app/webgen/pushspike/RawTapReceiver.kt` | Extras layout as observed/designed for the wire: see §5.0. |
| `~/workspace/web2apk/gecko-push/app/src/main/AndroidManifest.xml` | App-side receiver declarations (`exported=true`, five connector actions, twice: `PushReceiver` and `RawTapReceiver`). |
| `~/workspace/web2apk/gecko-push/PUSH-TEST-REPORT.md` | Device-proven flow against ntfy (`io.heckel.ntfy`): subscribe, endpoint issuance, persistence; Fix round 3 extras table and self-send (RFC 8291 `aes128gcm`) design. |

Confidence labels used throughout:

- **VERIFIED (artifact)** — read directly from `javap` bytecode /
  constant pool / AAR manifest of 3.3.5.
- **VERIFIED (app/report)** — present in the app sources or the test
  report named above.
- **UNVERIFIED** — not determinable from the supplied artifact or
  sources. Do not implement against these as if they were facts;
  they are marked so an implementer knows what still needs a
  device/ntfy check.

All string literals below are copied verbatim from the artifact's
constant pool (`ConstantsKt`, `UnifiedPush`, `MessagingReceiver`,
related classes), not from the UnifiedPush website or memory.

---

## 0. Master constant table (VERIFIED, artifact)

### 0.1 `org.unifiedpush.android.connector.ConstantsKt`

| Kotlin name | Exact value |
|---|---|
| `ACTION_NEW_ENDPOINT` | `org.unifiedpush.android.connector.NEW_ENDPOINT` |
| `ACTION_REGISTRATION_FAILED` | `org.unifiedpush.android.connector.REGISTRATION_FAILED` |
| `ACTION_TEMP_UNAVAILABLE` | `org.unifiedpush.android.connector.TEMP_UNAVAILABLE` |
| `ACTION_UNREGISTERED` | `org.unifiedpush.android.connector.UNREGISTERED` |
| `ACTION_MESSAGE` | `org.unifiedpush.android.connector.MESSAGE` |
| `ACTION_REGISTER` | `org.unifiedpush.android.distributor.REGISTER` |
| `ACTION_UNREGISTER` | `org.unifiedpush.android.distributor.UNREGISTER` |
| `ACTION_MESSAGE_ACK` | `org.unifiedpush.android.distributor.MESSAGE_ACK` |
| `EXTRA_APPLICATION` | `application` |
| `EXTRA_PI` | `pi` |
| `EXTRA_TOKEN` | `token` |
| `EXTRA_FEATURES` | `features` |
| `EXTRA_ENDPOINT` | `endpoint` |
| `EXTRA_MESSAGE_FOR_DISTRIB` | `message` |
| `EXTRA_REASON` | `reason` |
| `EXTRA_VAPID` | `vapid` |
| `EXTRA_BYTES_MESSAGE` | `bytesMessage` |
| `EXTRA_MESSAGE_ID` | `id` |
| `EXTRA_NEW_DISTRIBUTOR` | `newDistributor` |
| `WAKE_LOCK_TAG` | `android-connector:lock` |
| `TAG` | `UnifiedPush` |
| `INSTANCE_DEFAULT` | `default` |

### 0.2 Constants found elsewhere in the artifact

| Where | Exact value | Meaning |
|---|---|---|
| `UnifiedPush.FEATURE_BYTES_MESSAGE` (private) | `org.unifiedpush.android.distributor.feature.BYTES_MESSAGE` | The **only** feature string in the entire jar (searched every class). See §2. |
| `UnifiedPush.VAPID_REGEX` | `^[A-Za-z0-9_-]{87}$` | VAPID validation, §7. |
| `LinkActivityHelper.ACTION_PICK` | `UnifiedPush.pick` | Internal picker action of the connector's own `LinkActivity`; **not** distributor-facing. |
| `PushService.ACTION_PUSH_EVENT` | `org.unifiedpush.android.connector.PUSH_EVENT` | Internal app-side service binding; **not** distributor-facing. |
| AAR manifest, `RaiseToForegroundService` filter | `org.unifiedpush.android.connector.RAISE_TO_FOREGROUND` | Internal; **not** distributor-facing. |
| `UnifiedPush.register$connector` / `broadcastUnregister$connector` | Intent action `org.unifiedpush.dummy_app` | Action of the dummy Intent wrapped in the `pi` PendingIntent, §3.3. |
| `LinkActivityHelper` | URI `unifiedpush://link`, Intent action `android.intent.action.VIEW` | Default-distributor LINK resolution, §1.6. |
| `UnifiedPush.hasEmbeddedFcmDistributor` | Name prefixes `org.unifiedpush.android.embedded_fcm_distributor.` and `org.unifiedpush.android.foss_embedded_fcm_distributor.` | Embedded-distributor detection in the **client's own** package, §1.3. |
| AAR manifest `<queries>` only (no code constant) | `org.unifiedpush.android.distributor.LINK` | Listed alongside REGISTER/UNREGISTER for Android 11+ package visibility. No bytecode in 3.3.5 ever builds an Intent with this action. See §1.6 / UNVERIFIED U-3. |

---

## 1. Discovery — how the connector finds distributors

### 1.1 `UnifiedPush.getDistributors(context): List<String>` (VERIFIED, artifact)

Bytecode of `getDistributors`:

1. Calls `getResolveInfo$connector(context, action = "org.unifiedpush.android.distributor.REGISTER", package = null)`.
2. For each `ResolveInfo`, takes `resolveInfo.activityInfo.packageName` as a candidate distributor package name.
3. Special case: if the candidate package equals the **calling app's own package** and `hasEmbeddedFcmDistributor(context)` is true and `isPlayServicesAvailable(context)` is false, the candidate is dropped (mapped to null). See §1.3.
4. Logs `Found distributor with package name <pkg>` (tag `UnifiedPush`), collects non-null names, returns `distinct()` list.

There is **no other discovery query** in the jar: a search of all
disassembly for `queryIntent*`, `queryBroadcast*`, `resolve*` finds
only the calls documented here and in §1.6.

### 1.2 `getResolveInfo$connector(context, action, package?)` (VERIFIED, artifact)

This is the single query primitive (also reused internally, §5.6):

```text
intent = Intent(action)                       // action only, no data/component
if (package != null) intent.setPackage(package)
if (SDK_INT >= 33)
    pm.queryBroadcastReceivers(intent, ResolveInfoFlags.of(192L))
else
    pm.queryBroadcastReceivers(intent, 64)
filter: keep ResolveInfo ri only if
    ri.activityInfo.exported == true
    OR ri.activityInfo.packageName == context.packageName
```

Consequences, all VERIFIED from that bytecode:

- It queries **BroadcastReceivers** — `queryBroadcastReceivers`, not
  `queryIntentServices`, not `queryIntentActivities`. A distributor
  **service or activity alone is invisible** to discovery.
- For broadcast-receiver queries Android reports the receiver
  component through `ResolveInfo.activityInfo`; that is the field
  the connector reads for both `packageName` and `exported`.
- Flag values are the literal numbers in the bytecode: `192`
  (API ≥ 33, via `ResolveInfoFlags.of(192L)`) and `64` (below 33).
  In the classic `PackageManager` `GET_*` flag namespace those
  bits correspond to `GET_META_DATA (128) | GET_RESOLVED_FILTER (64)`
  and `GET_RESOLVED_FILTER (64)` respectively; the **numeric
  values** are the normative, bytecode-verified part — a
  distributor does not pass these flags anywhere, they only
  explain why disabled/until-used receiver components can still
  be returned by the query.
- A non-exported receiver in **another** package is filtered out.
  A non-exported receiver in the client's **own** package is kept
  (embedded-distributor case).

### 1.3 Embedded-distributor special case (VERIFIED, artifact)

`hasEmbeddedFcmDistributor(context)`:

- `pm.getPackageInfo(ownPackage, 6)` — flags `6 = GET_SERVICES(4) | GET_RECEIVERS(2)`.
- Collects all service class names **and** all receiver class names
  of the client's own package.
- Returns true iff any name starts with
  `org.unifiedpush.android.embedded_fcm_distributor.` or
  `org.unifiedpush.android.foss_embedded_fcm_distributor.`.

`isPlayServicesAvailable(context)`: true iff
`pm.getPackageInfo("com.google.android.gms", 1)` succeeds.

Net effect in `getDistributors`: an embedded FCM distributor inside
the client app itself is **hidden from the list when Play Services
is available**; it is listed when Play Services is absent. This
does not affect an external distributor app at all, except that a
distributor package name in the returned list is just a package
name — the connector never distinguishes "embedded" beyond this
rule and the §6 fallback logic, which treats the client's own
package as the "embedded" candidate.

### 1.4 What a distributor app MUST declare to be found (VERIFIED as the contrapositive of §1.1–§1.2)

```xml
<!-- Distributor app manifest — minimal discovery surface -->
<receiver
    android:name="<your receiver class>"
    android:exported="true">
    <intent-filter>
        <action android:name="org.unifiedpush.android.distributor.REGISTER" />
        <!-- Same receiver should also carry the other two
             distributor-side actions; the connector sends them
             with setPackage(yourPackage), see §3/§4/§5.5: -->
        <action android:name="org.unifiedpush.android.distributor.UNREGISTER" />
        <action android:name="org.unifiedpush.android.distributor.MESSAGE_ACK" />
    </intent-filter>
</receiver>
```

- `REGISTER` in a **receiver** intent-filter + `exported=true` is
  the hard requirement: it is the only thing `getDistributors`
  looks for.
- `UNREGISTER` / `MESSAGE_ACK` on the same receiver are not
  discovery requirements (discovery never queries them), but the
  connector addresses those broadcasts **only by package**
  (`setPackage(distributorPackage)`, no component), so *some*
  exported receiver in the distributor package must declare them
  or the broadcasts are undeliverable. Using the same receiver for
  all three is the obvious construction; whether ntfy uses one
  receiver or several is **UNVERIFIED** (U-1).
- Android 11+ package **visibility is the client's problem**, and
  the connector AAR already solves it for client apps: its merged
  manifest contains exactly this `<queries>` block (VERIFIED, AAR
  manifest):

  ```xml
  <queries>
      <intent><action android:name="org.unifiedpush.android.distributor.LINK" /></intent>
      <intent><action android:name="org.unifiedpush.android.distributor.REGISTER" /></intent>
      <intent><action android:name="org.unifiedpush.android.distributor.UNREGISTER" /></intent>
  </queries>
  ```

  A distributor needs no `<queries>` element of its own to be
  *found*. (It will need visibility of client packages if it ever
  queries them itself — not part of this contract; UNVERIFIED.)

### 1.5 What the distributor gets back from discovery

Nothing. Discovery is a one-way `PackageManager` query inside the
client; the distributor is not contacted, pinged, or version-checked
at discovery time. First contact is the `REGISTER` broadcast (§3).

### 1.6 Default-distributor resolution and the LINK activity (VERIFIED, artifact; distributor-side shape partly UNVERIFIED)

`UnifiedPush.resolveDefaultDistributor(context)`:

1. `LinkActivityHelper.Companion.resolveLinkActivityPackageName$connector(context, intent = null)`:
   - Builds (when intent is null) `Intent("android.intent.action.VIEW")` with `setData(Uri.parse("unifiedpush://link"))`.
   - `pm.resolveActivity(intent, 65536)` — `65536 = MATCH_DEFAULT_ONLY`.
   - Returns `resolveInfo.activityInfo.packageName`, or null.
2. If that returns null → `resolveWithoutLinkActivity(context)` (§6.4).
3. If it returns the literal string `"android"` (the system resolver
   itself, i.e. multiple/no default handlers) → `ResolvedDistributor.ToSelect` (user must pick).
4. Otherwise → `ResolvedDistributor.Found(packageName)`.

Therefore a distributor that wants to be offered as the **system
default** must expose an **Activity** that is the default handler
for `VIEW` + `unifiedpush://link`. That is the only LINK-related
behaviour the bytecode implements.

- **UNVERIFIED (U-3):** the exact distributor-side intent-filter for
  that activity (categories `DEFAULT`/`BROWSABLE`, host/path
  decomposition of `unifiedpush://link`, and whether an activity
  declaring action `org.unifiedpush.android.distributor.LINK`
  instead of / in addition to the VIEW deeplink is also honoured).
  The artifact only proves: `resolveActivity(VIEW, unifiedpush://link, MATCH_DEFAULT_ONLY)`.
  `MATCH_DEFAULT_ONLY` implies the filter must declare
  `android.intent.category.DEFAULT` to match, but the artifact does
  not state the distributor's filter.
- The LINK activity is **optional**. A distributor without one is
  still discovered (§1.1) and still selectable when it is the single
  external distributor (§6.4) or explicitly via `saveDistributor`.

---

## 2. Feature declarations

### 2.1 What exists in 3.3.5 (VERIFIED, artifact)

- Exactly **one** feature string exists in the whole jar:

  `org.unifiedpush.android.distributor.feature.BYTES_MESSAGE`
  (`UnifiedPush.FEATURE_BYTES_MESSAGE`, private constant).

- It is **never declared by the distributor anywhere the connector
  looks**: there is no meta-data key, no intent-filter convention,
  and no `PackageManager` meta-data read for features anywhere in
  the disassembly. A distributor cannot "declare" features to
  connector 3.3.5 through its manifest in any way this artifact
  will observe.
- It is **sent by the connector to the distributor**, on every
  `REGISTER`, as the `features` extra (§3.2): a `String[]` of
  length 1 containing exactly that string, unconditionally —
  not gated on distributor, API level, VAPID, or anything else.

### 2.2 What the distributor must do with it

- On `REGISTER`, read extra `features` (`String[]`). In 3.3.5 it
  will always be `["org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"]`.
- Meaning, as far as the connector's receiving code proves (§5.4):
  the distributor must deliver message payloads as a raw `byte[]`
  in the `bytesMessage` extra of `MESSAGE` broadcasts. The
  connector has **no other payload channel**: no String payload
  extra is ever read on `MESSAGE`.
- **UNVERIFIED (U-4):** what a distributor is supposed to do if it
  cannot honour `BYTES_MESSAGE` (e.g. deliver text anyway, or fail
  registration with which `reason`). The artifact defines no
  fallback and no error string for this case. Practically: a
  distributor that cannot forward opaque bytes cannot interoperate
  with 3.3.5 clients for message delivery.
- No VAPID feature, no "message ACK supported" feature, and no
  other feature strings exist in 3.3.5. Do not invent others and
  expect this connector to react to them.

---

## 3. REGISTER — connector → distributor

### 3.1 API surface that produces it (VERIFIED, artifact + app)

```text
UnifiedPush.register(context, instance = "default",
                     messageForDistributor = null, vapid = null)
UnifiedPush.registerApp(...)            // deprecated alias, identical
UnifiedPush.register(context, instance, messageForDistributor, vapid, keyManager)
```

App-side call actually used by PushBridge (VERIFIED, app):

```kotlin
UnifiedPush.register(context, instance, MSG_FOR_DISTRIBUTOR, vapid)
// MSG_FOR_DISTRIBUTOR = "Gecko Push Test (Aprica Phase-2 spike)"
// vapid = base64url(serverKey) when the page supplied one, else null
// instance = "g" + hex(scope.hashCode())  (app-chosen, opaque to connector/distributor)
```

Internal flow of `register(context, instance, message, vapid, keyManager)`
(VERIFIED, artifact):

1. `vapid` is normalised **before storing**: if non-null,
   `vapid.replace("=", "")` (all `=` padding stripped).
2. `DBStore.RegistrationsStore.set(instance, message, vapidStripped, keyManager, overrideTokens = null)`:
   - Upserts row `registrations(instance PRIMARY KEY, message TEXT, vapid TEXT)`.
   - Ensures Web Push keys exist for the instance (`keyManager.generate(instance)` if `!exists`).
   - For **every distributor currently in the distributor store**
     (primary + fallback-chain rows, §6), ensures a token row
     exists: `newToken(instance, distributor)` returns the existing
     token for `(instance, distributor)` if present, else inserts
     `UUID.randomUUID().toString()` (`RegistrationsStore.genToken`).
   - Returns one `Connection.Registration(distributor, token, RegistrationData(instance, message, vapid))` **per token row for the instance** — i.e. per known distributor.
3. For **each** returned Registration, `register$connector(context, registration)` is called. A single app-level `register()` can therefore emit **several** `REGISTER` broadcasts, one per known distributor, each with that distributor's own token. A distributor must not assume it is the only recipient of a registration, and must key all state by the `token` it receives (§8.4).

There is **no** "already registered" shortcut: every `register()`
call re-broadcasts `REGISTER` for every known distributor.

### 3.2 The REGISTER broadcast — exact construction (VERIFIED, artifact)

From `UnifiedPush.register$connector(context, Connection.Registration)`:

```text
// VAPID pre-check, before any intent is built:
if (registration.vapid != null && !VAPID_REGEX.matches(vapid))
    throw UnifiedPush.VapidNotValidException   // nothing is broadcast

pi = PendingIntent.getBroadcast(context, 0,
        Intent("org.unifiedpush.dummy_app"), 67108864)
// 67108864 = PendingIntent.FLAG_IMMUTABLE; requestCode 0

intent = Intent()                                 // no-arg constructor
intent.setPackage(registration.distributor)       // package-addressed, NO component/class
intent.setAction("org.unifiedpush.android.distributor.REGISTER")
intent.putExtra("token",       registration.token)                    // String
intent.putExtra("features",    arrayOf("org.unifiedpush.android.distributor.feature.BYTES_MESSAGE")) // String[]
intent.putExtra("application", context.packageName)                   // String
intent.putExtra("pi",          pi)                                    // PendingIntent (Parcelable)
if (registration.messageForDistributor != null)
    intent.putExtra("message", registration.messageForDistributor)    // String
if (registration.vapid != null)
    intent.putExtra("vapid",   registration.vapid)                    // String

if (SDK_INT >= 34)
    context.sendBroadcast(intent, null,
        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
else
    context.sendBroadcast(intent)
```

Extra inventory:

| Key | Type on the wire | Always present? | Meaning |
|---|---|---|---|
| `token` | `String` | Yes | Connector-generated registration token for this `(instance, distributor)` pair; `UUID.randomUUID().toString()` when first created, then **stable** — reused on every re-registration for the same pair until the registration/distributor state is removed (§6). The distributor must store it and echo it verbatim in **every** reply (§5). It is the *only* correlation key: there is no `instance` extra anywhere on the wire. |
| `features` | `String[]` | Yes | Always exactly `["org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"]` in 3.3.5. See §2. |
| `application` | `String` | Yes | The client app's package name (`context.packageName`). This is how the distributor learns where to address replies (§5.1). |
| `pi` | `android.app.PendingIntent` (Parcelable extra) | Yes | See §3.3. |
| `message` | `String` | Only if the app passed a non-null `messageForDistributor` | Per-app human-readable description/purpose string supplied to `register()`. PushBridge passes `"Gecko Push Test (Aprica Phase-2 spike)"`. Distributors typically show this in a registrations list / consent UI. Opaque to the protocol; may be absent. |
| `vapid` | `String` | Only if the app passed a non-null `vapid` (after `=` stripping, and only if it matched `^[A-Za-z0-9_-]{87}$`) | The application server's VAPID public key. Full treatment in §7. |

Delivery mechanism, explicitly:

- It is a **broadcast** (`Context.sendBroadcast`), **not**
  `startService`, not `bindService`, not an activity.
- Addressing is **package-only**: `setPackage(distributorPackage)`,
  action set, **no** `setComponent`/`setClassName`, no explicit
  receiver class. Any exported receiver in the distributor package
  with a matching filter receives it.
- No receiver permission is set (the 3-arg `sendBroadcast` overload
  passes `null` as the permission).
- On API ≥ 34 the broadcast is sent with
  `BroadcastOptions.setShareIdentityEnabled(true)`, i.e. the
  distributor is allowed to learn the sender's identity via the
  broadcast's sender information. Below 34, plain `sendBroadcast`.

### 3.3 The `pi` PendingIntent (VERIFIED construction; distributor use UNVERIFIED)

- Built identically in `register$connector` and
  `broadcastUnregister$connector`: dummy Intent with action
  `org.unifiedpush.dummy_app`, no package/component/data,
  `PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)`.
- Its creator package is therefore the **client app's package**;
  `pi.targetPackage` / creator identity reveals the client package
  independently of the `application` extra. (The connector itself
  uses exactly this trick in the LINK flow:
  `LinkActivityHelper.onLinkActivityResult` reads extra `pi` and
  calls `pendingIntent.targetPackage` to learn the chosen
  distributor package — VERIFIED, artifact.)
- **UNVERIFIED (U-5):** whether a distributor is expected to *send*
  this PendingIntent (and with what Intent/extras), store it, or
  merely use it as an authenticated client identity token. No
  connector bytecode sends, expects, or validates a `pi` being
  fired back. Replies are plain broadcasts (§5), not PendingIntent
  sends, in everything the artifact and the working ntfy flow
  show. An implementer should **accept and ignore** `pi` beyond
  optionally cross-checking `application`, until a distributor-side
  source says otherwise.

### 3.4 Distributor duties on REGISTER (derived from §§3.2, 5)

1. Parse `token`, `application`, `features`, optional `message`,
   optional `vapid`. Tolerate unknown extra keys (forward compat).
2. Create or **idempotently update** the registration keyed by
   `token` (re-REGISTER with the same token is routine, §3.1).
   Associate it with `application`; different tokens from the same
   `application` are different registrations (different instances).
3. Allocate an endpoint URL for this token (opaque to the
   connector, §8.1) and reply `NEW_ENDPOINT` (§5.2), or reply
   `REGISTRATION_FAILED` with a `reason` (§5.2) if it cannot.
4. If `features` lacks a capability the distributor requires, see
   U-4 — no defined behaviour in the artifact.

---

## 4. UNREGISTER — connector → distributor

### 4.1 API and internal flow (VERIFIED, artifact)

```text
UnifiedPush.unregister(context, instance = "default")
UnifiedPush.unregisterApp(...)          // deprecated alias
UnifiedPush.unregister(context, instance, keyManager)
```

Flow of `unregister(context, instance, keyManager)`:

1. Capture `tokens = RegistrationsStore.listToken(instance)` —
   the set of `Connection.Token(distributor, token)` for **all**
   distributors known for this instance — *before* deleting anything.
2. `RegistrationsStore.remove(instance, keyManager)`: deletes the
   `registrations` row and deletes the instance's Web Push keys
   (`keyManager.delete(instance)`); token rows cascade
   (`tokens` table has `FOREIGN KEY (instance) REFERENCES registrations(instance) ON DELETE CASCADE`).
   Returns the set of remaining instance names; **if no instances
   remain at all, the distributor store is cleared**
   (`DistributorStore.remove()` — all distributor rows).
3. For **each** captured token: `broadcastUnregister$connector(context, token)`.

### 4.2 The UNREGISTER broadcast — exact construction (VERIFIED, artifact)

```text
pi = PendingIntent.getBroadcast(context, 0,
        Intent("org.unifiedpush.dummy_app"), 67108864)   // FLAG_IMMUTABLE, as §3.3

intent = Intent()
intent.setPackage(token.distributor)
intent.setAction("org.unifiedpush.android.distributor.UNREGISTER")
intent.putExtra("token", token.token)   // String
intent.putExtra("pi",    pi)            // PendingIntent

// sent exactly like REGISTER: BroadcastOptions.setShareIdentityEnabled(true)
// on SDK >= 34, else plain sendBroadcast(intent)
```

| Key | Type | Always? | Notes |
|---|---|---|---|
| `token` | `String` | Yes | The same token previously sent in `REGISTER` for this distributor. |
| `pi` | `PendingIntent` | Yes | Same construction as §3.3. |

No `application`, no `features`, no `message`, no `vapid` extras
are sent on UNREGISTER. (The distributor already knows the
`token → application` mapping from REGISTER.)

### 4.3 Distributor duties on UNREGISTER

1. Look the registration up by `token`; delete it and stop
   delivering messages for it; release its endpoint.
2. Unknown `token`: tolerate silently — **UNVERIFIED (U-6)**
   whether any error reply is expected; the artifact has no
   error path for this, and the connector has already deleted its
   local state before broadcasting (§4.1), so it cannot act on one.
3. Replying `UNREGISTERED` (§5.2) is **optional in effect**: if the
   distributor sends it after an app-initiated UNREGISTER, the
   connector's `onReceive` will look the token up, find nothing
   (state already removed), and **return early without invoking
   any callback** (VERIFIED from the `onReceive` prologue, §5.0).
   The `UNREGISTERED` action's real purpose is the
   distributor-initiated path (§5.3).

### 4.4 Internal-only unregistration notice (not distributor-facing)

`UnifiedPush.broadcastLocalUnregistered(context, token)` sends a
broadcast **to the client's own package**
(`setPackage(context.packageName)`), action
`org.unifiedpush.android.connector.UNREGISTERED`, extra `token`
only. It is used when the saved distributor has vanished (§6.5) so
the app's own receiver learns its registrations died. A distributor
never sends or receives this; it is listed here only so an
implementer does not confuse it with §4.2.

---

## 5. Replies — distributor → connector

### 5.0 Common gating in `MessagingReceiver.onReceive` (VERIFIED, artifact)

Every reply action passes through the same prologue in
`org.unifiedpush.android.connector.MessagingReceiver.onReceive`.
A distributor that violates any of these gets **silence**, not an
error:

1. Extra `token` is read first via a type-safe wrapper
   (`getNullableStringExtra`: `intent.getStringExtra("token")`,
   any exception → null). If `token` is **null → return immediately**.
2. `token` is resolved via
   `RegistrationsStore.getInstance(token)`, which queries
   `tokens WHERE connectorToken = ?` and returns
   `Connection.Instance(instance, distributor)`. If there is
   **no row for that token → return immediately**, for *every*
   action. The distributor's identity is therefore never checked
   directly — possession of a live token *is* the routing key, and
   the `instance` string the app sees in callbacks comes from this
   lookup, never from a wire extra.
3. Only then is a wake lock acquired
   (`WakeLock`: `PowerManager.PARTIAL_WAKE_LOCK`, tag
   `android-connector:lock`, `acquire(10000)` ms) and the action
   dispatched. The lock is released at the end of `onReceive`
   (and on the early returns inside NEW_ENDPOINT / MESSAGE below).
4. All other extras are read with the same type-safe wrappers
   (`getStringExtra` / `getByteArrayExtra`, exception → null). A
   **wrong runtime type** for an extra is indistinguishable from
   the extra being absent. In particular a `String` in
   `bytesMessage` instead of `byte[]` reads as null (§5.4).
5. There is **no** check of the broadcast's sending package,
   no permission check, and no signature check in `onReceive`.

RawTapReceiver (VERIFIED, app) independently confirms the wire
shape from the app side: a plain `BroadcastReceiver` with the same
five-action filter, reading exactly `token` (String),
`bytesMessage` (byte[]), `endpoint` (String), `id` (String),
`reason` (String), plus a generic extras dump. Its class comment
states the extras layout was verified against connector 3.3.5
`ConstantsKt` + `MessagingReceiver.onReceive` bytecode.

### 5.1 How replies must be addressed (VERIFIED addressing requirement; ntfy construction UNVERIFIED)

- The connector publishes **no component name** for replies. Two
  different receivers in the working app — `PushReceiver` (a
  `MessagingReceiver` subclass) and `RawTapReceiver` (a plain
  `BroadcastReceiver`), plus the connector's own
  `MessagingReceiverImpl` — all declare the same five actions and
  the design/report premise (VERIFIED, app + report Fix round 3) is:

  > "Android delivers the distributor's explicit **package
  > broadcasts** to **every** matching receiver in the package"

  i.e. the distributor addresses replies with
  `Intent.setPackage(applicationPackageFromRegister)` and the
  action, **no `setComponent` / `setClassName`**. A distributor
  that hardcodes any one receiver class name would break the
  other receivers and the PushService variant (§5.6).
- Client-side receivers are `android:exported="true"` in both the
  app manifest (VERIFIED, app) and the AAR manifest for
  `MessagingReceiverImpl` (VERIFIED, artifact), which is what makes
  cross-package delivery possible. The distributor needs no
  permission to send these broadcasts in anything the artifact
  specifies.
- **UNVERIFIED (U-1):** ntfy's literal Intent construction
  (e.g. whether it also sets flags such as
  `FLAG_INCLUDE_STOPPED_PACKAGES`, or sends via a different
  mechanism for stopped apps). No ntfy code is in the supplied
  sources. The package-broadcast requirement above is what the
  connector + app jointly prove is *sufficient* and *expected*;
  matching flags are not observable from the artifact. An
  implementer should at minimum replicate package addressing and
  test delivery to a force-stopped client app on a real device.

### 5.2 Reply inventory — exact actions and extras (VERIFIED, artifact)

All actions are in the `org.unifiedpush.android.connector.*`
namespace (note: opposite namespace from REGISTER/UNREGISTER).

#### `org.unifiedpush.android.connector.NEW_ENDPOINT`

| Extra | Type | Required by connector? | Handling |
|---|---|---|---|
| `token` | `String` | **Yes** (§5.0) | Routes to instance. |
| `endpoint` | `String` | **Yes** — if null, `onReceive` returns silently, no callback, no ack. | Becomes `PushEndpoint.url`. Connector treats it as opaque (never parsed, §8.1). |
| `id` | `String` | No | If non-null, after the callback the connector sends a `MESSAGE_ACK` for this id (§5.5), exactly as for a message. |

Handling sequence (VERIFIED): read `endpoint`, read `id`;
`pubKeySet = keyManager.getPublicKeySet(instance)` (the connector's
**own** keys — the distributor never sends key material);
`DistributorStore.ack(token's distributor)` — sets that
distributor's `ack = 1` and returns the tokens of the
fallback-chain distributors superseded by it, each of which is
then sent a `broadcastUnregister` (§4.2) — see §6.3;
`temporary = !DistributorStore.isPrimary(token's distributor)`;
dispatch `onNewEndpoint(context, PushEndpoint(url = endpoint, pubKeySet, temporary), instance)`;
then `mayAcknowledgeMessage(...)` (§5.5).

Distributor guidance: send `NEW_ENDPOINT` once per successful
REGISTER, and again (same `token`) whenever the endpoint for that
token changes — the app side treats a changed URL under a live
subscription as a rotation (PushBridge calls
`Web PushController.onSubscriptionChanged`, VERIFIED app).
Re-sending the same endpoint is harmless (idempotent overwrite
app-side).

#### `org.unifiedpush.android.connector.MESSAGE`

| Extra | Type | Required by connector? | Handling |
|---|---|---|---|
| `token` | `String` | **Yes** (§5.0) | Routes to instance. |
| `bytesMessage` | **`byte[]`** (`getByteArrayExtra`) | **Yes** — if null, `onReceive` returns silently. | The payload, still encrypted if it is a Web Push body; see §5.4. |
| `id` | `String` | No | If non-null, echoed in the `MESSAGE_ACK` sent after delivery (§5.5). Absent id ⇒ no ack is ever sent. |

#### `org.unifiedpush.android.connector.REGISTRATION_FAILED`

| Extra | Type | Required by connector? | Handling |
|---|---|---|---|
| `token` | `String` | **Yes** (§5.0) | Routes to instance. |
| `reason` | `String` | No (but see mapping) | Converted by `FailedReasonKt.toFailedReason`: `FailedReason.valueOf(reason)`; on null or `IllegalArgumentException` → `INTERNAL_ERROR`. |

Valid `reason` values are exactly the `FailedReason` enum names
(VERIFIED, artifact):

```text
INTERNAL_ERROR
NETWORK
ACTION_REQUIRED
VAPID_REQUIRED
```

Any other string is silently coerced to `INTERNAL_ERROR` — spelling
and case must match exactly (`valueOf` is case-sensitive).
Dispatch: `onRegistrationFailed(context, reason, instance)`.
No ack, no state deletion in the connector on this action
(the registration row and token remain; the app decides what to do).

#### `org.unifiedpush.android.connector.UNREGISTERED`

| Extra | Type | Required by connector? | Handling |
|---|---|---|---|
| `token` | `String` | **Yes** (§5.0) | Routes to instance. |
| `newDistributor` | `String` (a package name) | No — its presence switches the whole branch, §5.3. |  |

- Without `newDistributor`: the connector locally unregisters the
  instance (`UnifiedPush.unregister(context, instance, keyManager)`
  — which also fires §4.2 UNREGISTER broadcasts for the instance's
  tokens, possibly including back to the sending distributor),
  then dispatches `onUnregistered(context, instance)`.
- With `newDistributor`: migration branch, §5.3.

This action is the distributor-initiated removal path: use it when
the distributor has deleted / lost the registration on its side.

#### `org.unifiedpush.android.connector.TEMP_UNAVAILABLE`

| Extra | Type | Required by connector? | Handling |
|---|---|---|---|
| `token` | `String` | **Yes** (§5.0) | Routes to instance. |
| `newDistributor` | `String` (a package name) | No — its presence switches the whole branch, §5.3. |  |

- Without `newDistributor`: dispatch `onTempUnavailable(context, instance)`
  (default implementation in `MessagingReceiver` is a no-op; apps
  may override). Nothing else happens — no state change, no
  re-registration.
- With `newDistributor`: fallback branch, §5.3.

### 5.3 `newDistributor` migration semantics (VERIFIED, artifact)

Both `UNREGISTERED` and `TEMP_UNAVAILABLE` read
`newDistributor = intent.getStringExtra("newDistributor")`. If
non-null (only nullness is tested — an empty string is not
special-cased in the bytecode), the connector runs the
distributor-migration machinery instead of the plain callback:

For `TEMP_UNAVAILABLE`:
`DistributorStore.setFallback(currentDistributor, newDistributor)`
records `newDistributor` as a fallback **from** the current
distributor and returns `Pair(changed, tokensToUnregister)`:

- Each token in `tokensToUnregister` is sent a §4.2 UNREGISTER
  broadcast (to its recorded distributor).
- If `changed` is true (the fallback was newly established, not
  already recorded), **every stored registration** is re-run:
  for each `RegistrationData(instance, message, vapid)` in the
  registrations store, `RegistrationsStore.set(...)` is re-applied
  (ensuring tokens/keys for the now-extended distributor set) and
  `register$connector` is invoked for each resulting Registration
  whose distributor equals `newDistributor`. Effectively: the
  client re-REGISTERs all its instances with the fallback
  distributor, with fresh per-distributor tokens, stored `message`
  and stored `vapid` — the distributor of last resort receives
  ordinary §3 REGISTER broadcasts and should treat them normally.
- `CyclicFallbackException` from `setFallback` (a fallback cycle)
  is caught; in that case (and when `changed` is false) the
  connector falls through to dispatching `onTempUnavailable`.

For `UNREGISTERED`:
`DistributorStore.setPrimary(newDistributor)` makes
`newDistributor` the primary distributor, returning
`Pair(changed, tokensToUnregister)`:

- Each token in `tokensToUnregister` is sent a §4.2 UNREGISTER.
- If `changed` is true, **every stored registration** is
  re-registered by literally calling
  `UnifiedPush.register(context, instance, messageForDistributor, vapid, keyManager)`
  for each stored `RegistrationData` — again surfacing at the new
  distributor as ordinary §3 REGISTER broadcasts (to every
  distributor then in the store, per §3.1).

Distributor guidance: only send `newDistributor` if you are
deliberately handing the client off to another installed
distributor package (the value must be that package's exact name).
Sending your own package name, or a non-installed package, is
**UNVERIFIED (U-7)** in effect beyond the mechanics above
(cycle handling exists, so self/cyclic references are at least
anticipated by the connector).

### 5.4 MESSAGE payload handling and decryption (VERIFIED, artifact)

In the `MESSAGE` branch:

```text
bytes = getByteArrayExtra("bytesMessage")   // null -> return
id    = getStringExtra("id")                // may be null
try {
    decryptedBytes = keyManager.decrypt(instance, bytes)
    message = (decryptedBytes != null)
        ? PushMessage(content = decryptedBytes, decrypted = true)
        : PushMessage(content = bytes,          decrypted = false)
} catch (GeneralSecurityException e) {
    Log.w("UnifiedPush", "Could not decrypt message, trying with plain text. Cause: " + e.message)
    message = PushMessage(content = bytes, decrypted = false)
}
onMessage(context, message, instance)
mayAcknowledgeMessage(context, distributor, id, token)
```

Consequences for a distributor:

- The distributor transports the push body **byte-for-byte opaque**.
  Encryption/decryption (RFC 8291 `aes128gcm` via the connector's
  vendored Tink `fixed_webpush` decryptor and its per-instance
  managed keys) is entirely a client/application-server matter.
- A plaintext (unencrypted) payload is **not** rejected: decryption
  failure degrades to delivering the original bytes with
  `decrypted = false`. Apps may still accept it (PushBridge
  forwards it and logs the flag, VERIFIED app).
- An **empty** `byte[]` is not special-cased before decryption;
  PushBridge treats decrypted empty content as a data-less
  "tickle" push (VERIFIED, app). Whether an empty array survives
  the decrypt attempt as empty-with-`decrypted=false` is a
  connector/Tink detail, not a distributor concern — the
  distributor should forward empty bodies rather than dropping
  them if the push service delivered one. **UNVERIFIED** for the
  exact PushMessage an empty body yields.

### 5.5 MESSAGE_ACK — connector → distributor (VERIFIED, artifact)

`MessagingReceiver.mayAcknowledgeMessage(context, distributor, id, token)`
is invoked after `NEW_ENDPOINT` handling and after `MESSAGE`
handling, with the `id` extra from the incoming broadcast:

```text
if (id == null) return                       // no ack without an id
intent = Intent()
intent.setPackage(distributor)               // distributor of the token's registration
intent.setAction("org.unifiedpush.android.distributor.MESSAGE_ACK")
intent.putExtra("token", token)              // String, the incoming token
intent.putExtra("id",    id)                 // String, echoed verbatim
context.sendBroadcast(intent)                // plain, no BroadcastOptions variant
```

- Distributor must have an exported receiver for
  `org.unifiedpush.android.distributor.MESSAGE_ACK` (§1.4) if it
  wants acknowledgements.
- The ack carries **no** result/status extra: it asserts only
  "this `(token, id)` was received and dispatched by the
  connector". For `MESSAGE` it is sent *after* `onMessage`
  returns; for `NEW_ENDPOINT` after `onNewEndpoint` returns.
- If the distributor never sets `id`, it will never receive an
  ack. If it needs delivery confirmation, it **must** set a
  unique `id` per message (§8.2).
- **UNVERIFIED (U-8):** any distributor-side obligation after an
  ack (e.g. deleting a queued message, stopping retries). The
  artifact shows only the sending side.

### 5.6 The second receiving path: `MessagingReceiverImpl` / `PushService` (VERIFIED, artifact)

Client apps may either subclass `MessagingReceiver` themselves
(the PushBridge way) **or** subclass `PushService`. The connector
ships an internal receiver for the second case:

- AAR manifest declares
  `org.unifiedpush.android.connector.internal.MessagingReceiverImpl`,
  `exported=true`, `enabled=true`, intent-filter **priority `-500`**
  with the same five connector actions.
- Its `shouldRun(context)` (cached in a static `Boolean`):
  `getResolveInfo$connector(context, "org.unifiedpush.android.connector.MESSAGE", ownPackage)`;
  returns **false** if any resolved receiver in the own package has
  `priority > -500` (i.e. an app-supplied receiver takes
  precedence), **true** if the list is empty or all priorities are
  `<= -500`.
- When it runs, it executes `MessagingReceiver.onReceive` on a
  single-thread executor (`goAsync()`), and its overrides forward
  each callback as an event to a bound `PushService` via
  `InternalPushServiceConnection` (binding Intent action
  `org.unifiedpush.android.connector.PUSH_EVENT`, `setPackage(ownPackage)`).

Distributor relevance: none at the wire level — replies are
identical for both app styles. This is why §5.1 forbids addressing
replies to a specific receiver class: the correct recipient class
varies by app and is decided inside the client package.

---

## 6. Distributor selection state (connector-side, 3.3.5)

Understanding this section is what tells a distributor *when* to
expect REGISTER/UNREGISTER traffic and what "current distributor"
means. Storage is a SQLite DB named `unifiedpush-connector`,
version 1 (VERIFIED schema):

```sql
CREATE TABLE distributors (distributor TEXT PRIMARY KEY, fallback_from TEXT, ack INTEGER, date_insertion INTEGER, FOREIGN KEY (fallback_from) REFERENCES distributors(distributor) ON DELETE CASCADE);
CREATE TABLE registrations (instance TEXT PRIMARY KEY, message TEXT, vapid TEXT);
CREATE TABLE tokens (connectorToken TEXT PRIMARY KEY, instance TEXT, distributor TEXT, FOREIGN KEY (instance) REFERENCES registrations(instance) ON DELETE CASCADE, FOREIGN KEY (distributor) REFERENCES distributors(distributor) ON DELETE CASCADE);
CREATE TABLE keys (instance TEXT PRIMARY KEY, auth TEXT, pubkey TEXT, privkey TEXT, iv TEXT, FOREIGN KEY (instance) REFERENCES registrations(instance) ON DELETE CASCADE);
```

(Column-name note: the DB column is spelled `connectorToken`; the
wire extra is `token`. Do not confuse the two namespaces.)

### 6.1 Vocabulary in the store (VERIFIED, artifact)

- A **distributor row** = `(packageName, fallback_from, ack, date_insertion)`.
  `fallback_from = NULL` rows are primary candidates; a row with
  `fallback_from = X` is a fallback *for* X.
- **Primary distributor**: the row returned by
  `DistributorStore.get()` — used with `isPrimary(pkg)` to set
  `PushEndpoint.temporary = !isPrimary(...)` (§5.2).
- **Ack flag**: `ack = 1` is set **only** by
  `DistributorStore.ack(distributor)`, which is called **only**
  when a `NEW_ENDPOINT` arrives bearing a token belonging to that
  distributor (§5.2). Saving/selecting a distributor does **not**
  set it. `ack()` also deletes the rows that were fallbacks *to*
  that distributor (`DELETE FROM distributors WHERE fallback_from = ?`)
  after collecting their tokens (`getFallbackToChain`, a recursive
  CTE over `fallback_from`) so the caller can UNREGISTER them.

### 6.2 `saveDistributor` / `getSavedDistributor` / `getAckDistributor` (VERIFIED, artifact)

```text
saveDistributor(context, distributor):
    (changed, tokensToUnregister) = DistributorStore.setPrimary(distributor)
    // setPrimary: if the row already exists (as primary), clears its
    // fallback_from; otherwise inserts it as primary. Pair.first in
    // the store's internal convention is inverted by callers to mean
    // "this call newly established the primary".
    for token in tokensToUnregister: broadcastUnregister(token)   // §4.2

getSavedDistributor(context): String?   // may return null in Kotlin terms;
                                        // PushBridge reads it as String and
                                        // null-coalesces to "" (VERIFIED, app)
    = private getDistributor(context, db, requireAck = false)

getAckDistributor(context): String?
    = private getDistributor(context, db, requireAck = true)
```

Private `getDistributor(context, db, requireAck)` (§6.5 for the
cleanup behaviour) returns the primary distributor's package name,
except: when `requireAck` is true it returns the distributor
**only if its `ack` flag is set** (or the stored distributor is
null); with `requireAck = false` it returns the stored primary
regardless of ack.

So, precisely:

- "**Saved distributor**" = a primary row exists (post-cleanup).
- "**Current (ack) distributor**" = a primary row exists **and**
  that distributor has proved itself by returning at least one
  `NEW_ENDPOINT`. This is the notion `tryUseCurrentOrDefaultDistributor`
  uses.

### 6.3 `tryUseCurrentOrDefaultDistributor` (VERIFIED, artifact)

```text
tryUseCurrentOrDefaultDistributor(context, callback):
    if (getAckDistributor(context) != null)
        callback(true)                       // done: no save, no register, no broadcast
    else
        tryUseDefaultDistributor(context, callback)
```

PushBridge's usage (VERIFIED, app): called only when distributors
exist but none is saved; a `false` result aborts the subscription
attempt with an error. Note the asymmetry a distributor should
expect: a client that has *saved* you but never received an
endpoint from you will fall through to default resolution on the
next `tryUseCurrentOrDefaultDistributor` call, which may re-save
you (no-op) or pick someone else.

### 6.4 `tryUseDefaultDistributor`, `tryPickDistributor`, default resolution (VERIFIED, artifact)

```text
tryUseDefaultDistributor(context, callback):
    when (resolveDefaultDistributor(context)) {
        is ToSelect       -> set LinkActivity callback; startActivity(
                                 Intent().setClassName(ownPackage,
                                     org.unifiedpush.android.connector.internal.LinkActivity))
                             // user picks via the LINK activity, §1.6;
                             // on success saveDistributor(chosen) is called
                             // from LinkActivityHelper.onLinkActivityResult
        is Found(pkg)     -> saveDistributor(context, pkg); callback(true)
        is NoneAvailable  -> callback(false)
    }

resolveDefaultDistributor(context):            // §1.6
    pkg = resolveLinkActivityPackageName(VIEW unifiedpush://link)
    if (pkg == null)  -> resolveWithoutLinkActivity(context)
    if (pkg == "android") -> ToSelect
    else -> Found(pkg)

resolveWithoutLinkActivity(context):
    all = getDistributors(context)
    external = all - ownPackage
    if (all.isEmpty())        -> NoneAvailable   // log: "No distributor available on the system"
    if (all.size() == 1)      -> Found(all.first())  // log: "...probably an embedded distrib."
    if (external.size() == 1) -> Found(external.first())  // log: "Found a single external (legacy) distributor without LinkActivity"
    else                      -> NoneAvailable   // log: "Found N external (legacy) distributors without LinkActivity, can't select one"
                               // NOTE: returns NoneAvailable, not ToSelect, in this branch

tryPickDistributor(context, callback):
    // always goes through the LINK picker: resolves the LINK activity,
    // sets the internal LinkActivity callback, and startActivity()s
    // LinkActivity with action "UnifiedPush.pick" (startPickLinkActivityForResult,
    // which shows a chooser titled "UnifiedPush").
```

`Activity` overloads of all three `tryUse*`/`tryPick*` functions
delegate to the `Context` overloads (VERIFIED). PushBridge calls
the `Context` overload of `tryUseCurrentOrDefaultDistributor`.

Selection consequences for a distributor:

- With **no LINK activity anywhere**: exactly-one-distributor
  systems self-select; multi-distributor systems fail selection
  (`callback(false)`) instead of prompting, unless the app calls
  `tryPickDistributor` / `saveDistributor` explicitly.
- With a **LINK activity**: its package wins by default, or the
  system resolver (`"android"`) triggers the interactive picker.

### 6.5 Distributor removal / disappearance (VERIFIED, artifact)

Private `getDistributor` (backing both getters in §6.2) reconciles
the store with `getDistributors(context)` on **every** call:

1. `stored = DistributorStore.list()`; `installed = getDistributors(context)`.
2. If `stored` is non-empty and **none** of the stored distributors
   is in `installed`:
   log `There was a distributor, but it isn't installed anymore`;
   for **every** token in the tokens table,
   `broadcastLocalUnregistered(context, token)` (§4.4 — a local
   `UNREGISTERED` broadcast to the client's own package); return
   null. (No UNREGISTER broadcast is sent to the vanished
   distributor — it is gone.)
3. Otherwise, each stored distributor that is **not** in
   `installed` is pruned (`DistributorStore.remove(packageName)`;
   its tokens cascade-delete via the FK), and the surviving
   primary is returned subject to the ack rule in §6.2.

Related removal APIs (VERIFIED):

```text
removeDistributor(context[, keyManager]):
    for instance in RegistrationsStore.listInstances(): unregister(context, instance, keyManager)
    RegistrationsStore.removeAll(keyManager)     // deletes all registrations + keys
    DistributorStore.remove()                    // deletes all distributor rows
forceRemoveDistributor(context):
    removeDistributor(context) with a DefaultKeyManager — same as above
```

And the interaction from §4.1: when the **last** instance is
unregistered, the distributor store is cleared as a side effect —
so a distributor should expect that a client which unregisters
everything "forgets" it entirely and will re-run selection (and
re-REGISTER with a **new** token, since the token rows are gone)
on the next subscribe.

### 6.6 When registration is (re-)attempted — summary (VERIFIED)

A distributor will receive §3 REGISTER broadcasts for an instance
when **any** of these happens:

1. The app calls `UnifiedPush.register(...)` — always, for every
   distributor in the store, no deduplication (§3.1).
2. A `TEMP_UNAVAILABLE` with `newDistributor` newly establishes a
   fallback — re-registration targeted at the fallback (§5.3).
3. An `UNREGISTERED` with `newDistributor` newly establishes a new
   primary — re-registration of all stored instances (§5.3).
4. (App-level, not connector-mandated) PushBridge re-calls
   `register` on every fresh Gecko `onSubscribe` for a scope with
   no persisted endpoint (VERIFIED, app).

It will receive §4 UNREGISTER broadcasts when: the app unregisters
an instance (all its distributor tokens), a new primary/fallback
supersedes old rows (`saveDistributor`, `setPrimary`,
`setFallback`, `ack` cleanup, §5.2/§5.3/§6.2), or the client
removes its distributor entirely (§6.5).

---

## 7. VAPID

### 7.1 What the connector does with the `vapid` argument (VERIFIED, artifact)

End-to-end, in order:

1. **API**: `register(context, instance, messageForDistributor, vapid)`;
   `vapid = null` is the default. PushBridge passes
   `base64url(serverKey)` — the Web Push `applicationServerKey`
   bytes, URL-safe Base64 without padding — when the page supplied
   a server key, else null (VERIFIED, app).
2. **Normalisation**: in `register(..., keyManager)`,
   `vapid?.replace("=", "")` — all `=` characters are removed
   before anything else. A padded Base64url key therefore arrives
   at the distributor unpadded.
3. **Storage**: the normalised value is stored in
   `registrations.vapid` (§6 schema) and is re-used verbatim for
   every later automatic re-registration (§5.3, §6.6).
4. **Validation**: in `register$connector`, *before* broadcasting,
   if `vapid != null && !Regex("^[A-Za-z0-9_-]{87}$").matches(vapid)`
   the connector **throws `UnifiedPush.VapidNotValidException`**
   and broadcasts nothing (to any distributor — validation is per
   Registration, and each Registration for the same instance
   carries the same vapid). 87 chars is the unpadded Base64url
   length of a 65-byte uncompressed P-256 public key.
5. **Forwarding**: if `vapid != null` (post-normalisation, post-
   validation), `register$connector` adds
   `intent.putExtra("vapid", vapid)` (`String`) to the REGISTER
   broadcast. If null, the extra is **absent** (not empty).

### 7.2 What a distributor must do to receive it (VERIFIED — there is no gate)

- **Nothing.** There is no feature flag, manifest declaration,
  or capability negotiation for VAPID in 3.3.5. The
  `features` extra (§2) contains only `BYTES_MESSAGE`; the
  connector does **not** check any distributor-declared VAPID
  feature before forwarding, and does not withhold `vapid` from
  distributors that ignore it.
- To use it, the distributor simply reads the optional `vapid`
  String extra on REGISTER and stores it with the registration
  (e.g. to verify RFC 8292 `Authorization` signatures from the
  application server when pushes arrive at the endpoint).
- If a distributor **requires** VAPID and a REGISTER arrives
  without the `vapid` extra, the defined reply channel is
  `REGISTRATION_FAILED` with extra `reason` = `VAPID_REQUIRED`
  (§5.2 — the enum value exists in 3.3.5 for exactly this name).
  **UNVERIFIED (U-9)** in the narrow sense that the artifact
  proves the *value* and its parsing, but contains no
  distributor-side rule stating when it must be sent; the name
  and the connector's callback surface make the intent
  unambiguous.
- A distributor must tolerate `vapid` changing or disappearing
  across re-REGISTERs of the same token (the stored value is
  whatever the app's latest `register()` call supplied).

---

## 8. Everything else an implementer must mirror

### 8.1 Endpoint semantics (VERIFIED connector behaviour; ntfy form observed)

- The connector **never parses, validates, or normalises** the
  endpoint string: it is stored nowhere in the connector DB; it is
  wrapped in `PushEndpoint.url` and handed to the app. Any string
  the distributor sends is accepted (non-null is the only
  requirement, §5.2).
- Observed ntfy endpoints in the working flow (VERIFIED, report):
  `https://ntfy.sh/upr5mYIJURiu4q?up=1` and, after a later
  re-subscribe, `https://ntfy.sh/upn97SeGoz0EdM?up=1` — i.e. the
  endpoint embeds ntfy's topic/token material and the `?up=1`
  marker, and it **changes between subscriptions** while remaining
  stable for a live one (the subscription "persists across app
  restarts" with the same endpoint, per the report). A new
  distributor should follow the same shape of contract: endpoint
  = a single opaque HTTPS URL, unique per registration, stable
  until rotation, rotatable by re-sending `NEW_ENDPOINT`.
- The application server POSTs the (encrypted) push body to this
  URL; everything after that — server → distributor → §5.2
  `MESSAGE` broadcast — is distributor-internal and invisible to
  the artifact. **UNVERIFIED (U-10):** ntfy's internal mapping
  (topic ↔ token, HTTP status behaviour such as the 507 lag noted
  in project memory for freshly-created ntfy topics) is not in the
  supplied artifact/sources and is not part of this contract.

### 8.2 Message `id` semantics (VERIFIED connector behaviour)

- `id` is an **opaque `String`** to the connector. It is never
  parsed, compared, deduplicated, ordered, or stored.
- Its only uses: (a) echoed verbatim in `MESSAGE_ACK` (§5.5) for
  both `MESSAGE` and `NEW_ENDPOINT`; (b) in the `NEW_ENDPOINT`
  branch it is read before dispatch but otherwise unused.
- Consequences: if the distributor wants ack correlation,
  retries, or dedup, **it** must mint unique ids and track them;
  the connector provides no sequencing and will deliver duplicate
  broadcasts as duplicate `onMessage` calls.
- App-side observation (VERIFIED, app): RawTapReceiver logs `id`
  on MESSAGE arrivals alongside `token` and `bytesMessage` size;
  PushBridge does not consume the id at all (it is below the
  `MessagingReceiver` abstraction).

### 8.3 `byte[]` vs Base64/String — the payload is binary (VERIFIED)

- `bytesMessage` is read **only** via `getByteArrayExtra`. Sending
  it as a Base64 `String`, a `CharSequence`, or any other type
  yields null and the MESSAGE is **silently dropped** (§5.0.4).
- The same type-strictness applies to every extra in both
  directions: all other contract extras are `String` (except
  `features`, `String[]`, and `pi`, `PendingIntent`). Use
  `putExtra(String, String)`, `putExtra(String, String[])`,
  `putExtra(String, byte[])` exactly.
- The push body the distributor forwards must be the **raw bytes**
  of the application server's POST body (for Web Push, the
  `aes128gcm` ciphertext blob: `salt(16) || rs(4) || idlen(1) ||
  sender public key(65) || ciphertext`, per the report's self-send
  construction). No transfer-encoding (Base64 etc.) is applied or
  expected on the broadcast.

### 8.4 Tokens, instances, and multiplicity (VERIFIED)

- One app can hold **many instances** (PushBridge: one per
  service-worker scope) and the connector mints **one token per
  (instance, distributor)**. A distributor will see many tokens
  per `application`, and must keep registrations strictly per-token.
- Tokens are UUID-formatted in 3.3.5 (`UUID.randomUUID().toString()`),
  but a distributor must treat them as **opaque strings**: do not
  validate the format, do not derive anything from them, and key
  storage by the exact string.
- Tokens are stable across re-REGISTER, endpoint rotation, and
  app restarts, but are destroyed when the registration or the
  distributor relationship is removed (§§4.1, 6.5). After such
  removal, the same app+instance reappears under a **new** token;
  a distributor must not try to resurrect the old mapping.
- There is no token expiry, heartbeat, or keepalive in the
  connector. **UNVERIFIED (U-11):** whether/when a distributor may
  proactively expire idle registrations; the only defined
  distributor-initiated teardown is `UNREGISTERED` (§5.2).

### 8.5 Timing, process, and lifecycle expectations (VERIFIED)

- Replies are broadcasts and **cold-start** the client app
  process; the connector holds a 10-second partial wake lock
  (`android.permission.WAKE_LOCK` is declared by the connector
  AAR itself) while dispatching. The distributor does not need
  to (and cannot) hold the client in the foreground.
- `NEW_ENDPOINT` may arrive at any time relative to app activity;
  the connector handles it in the receiver, not in an activity.
- The connector gives the distributor **no** delivery timing
  guarantee knobs on the wire: there are no TTL, urgency,
  topic/thread, or collapse-key extras anywhere in Constants or
  `onReceive`. Any such push-service headers are consumed (or
  ignored) inside the distributor and never forwarded by this
  contract.

### 8.6 Security posture the artifact actually implements (VERIFIED)

- REGISTER/UNREGISTER are package-addressed broadcasts from the
  connector with no receiver permission; on API ≥ 34 the
  connector enables broadcast identity sharing
  (`setShareIdentityEnabled(true)`), so a distributor on API 34+
  can identify the sending app from the broadcast itself in
  addition to the `application` extra and `pi` creator.
- Replies are accepted from **any** sender that knows a live
  token (§5.0.5). Token confidentiality is therefore load-bearing:
  a distributor must not leak tokens (logs, endpoints, other
  apps) and must only send replies to the `application` package
  recorded for that token.
- **UNVERIFIED (U-12):** any additional hardening ntfy applies
  (e.g. checking the REGISTER sender package against
  `application`). The artifact neither requires nor forbids it;
  note that `application` is a self-asserted extra in a broadcast,
  while the API-34 broadcast identity and the `pi` creator package
  are system-asserted.

### 8.7 Minimal distributor checklist

1. Exported `BroadcastReceiver` with `REGISTER` (and, on some
   exported receiver in the package, `UNREGISTER` and
   `MESSAGE_ACK`) intent-filters — §1.4.
2. Optional: default-handler Activity for `VIEW` +
   `unifiedpush://link` to participate in default selection — §1.6 (shape U-3).
3. On REGISTER: store `token → {application, message?, vapid?, features}`,
   idempotently; allocate endpoint; reply NEW_ENDPOINT or
   REGISTRATION_FAILED — §§3, 5.2.
4. On push arrival: broadcast `MESSAGE` to package `application`
   with `token`, `bytesMessage` = raw `byte[]`, and a unique
   `id` if you want a MESSAGE_ACK — §§5.2, 5.5, 8.2, 8.3.
5. On UNREGISTER: delete by `token`; tolerate unknown tokens — §4.
6. On MESSAGE_ACK: mark `(token, id)` delivered — §5.5 (obligations U-8).
7. Distributor-initiated teardown / handoff: `UNREGISTERED` /
   `TEMP_UNAVAILABLE`, optionally with `newDistributor` — §§5.2, 5.3.
8. Never address replies to a receiver class name; never put the
   payload in a String extra; never invent extra keys the
   connector is known to read differently — §5.

---

## Appendix A. Consolidated UNVERIFIED list

| ID | Item | Why it cannot be settled here |
|---|---|---|
| U-1 | ntfy's exact reply/REGISTER-receiver Intent construction (flags, one-vs-many receivers, stopped-package handling) | No distributor code in the supplied sources; the artifact only proves what the connector sends and accepts. |
| U-3 | Exact distributor-side intent-filter for the LINK/default activity (categories, data decomposition), and any effect of declaring action `org.unifiedpush.android.distributor.LINK` | Only the client-side `resolveActivity(VIEW, unifiedpush://link, MATCH_DEFAULT_ONLY)` and the AAR `<queries>` entry exist. |
| U-4 | Required distributor behaviour when it cannot honour `features = [BYTES_MESSAGE]` | No string, branch, or error path for it in the artifact. |
| U-5 | Any required distributor use of the `pi` PendingIntent (sending it, storing it) | Constructed and sent by the connector; never consumed on the connector side for REGISTER/UNREGISTER replies. |
| U-6 | Any expected reply to an UNREGISTER for an unknown token | Connector deletes local state before broadcasting UNREGISTER; no error channel exists. |
| U-7 | Practical effect of `newDistributor` naming an uninstalled package or the sender itself | Mechanics (§5.3) and cycle exception are verified; outcomes for those values are not exercised by any supplied source. |
| U-8 | Distributor obligations after receiving MESSAGE_ACK (dequeue, stop retrying) | Artifact shows only the connector sending the ack. |
| U-9 | The normative rule "send REGISTRATION_FAILED/VAPID_REQUIRED when VAPID is required but absent" | The enum value and parsing are verified; the distributor-side rule is not in the artifact. |
| U-10 | ntfy-internal behaviour (endpoint/topic mapping, HTTP responses, activation lag) | Server/distributor internals are outside the artifact and the app sources. |
| U-11 | Distributor-side registration expiry policy | No expiry mechanism exists connector-side. |
| U-12 | Extra sender-authentication ntfy may perform on REGISTER | Not required or described by the artifact. |

(U-2 was merged into U-1 during drafting; numbering is stable as above.)

## Appendix B. Wire summary (one screen)

```text
CONNECTOR -> DISTRIBUTOR  (broadcast, setPackage(distributorPkg), no component)
  org.unifiedpush.android.distributor.REGISTER
      token:String  features:String[]=[...feature.BYTES_MESSAGE]
      application:String  pi:PendingIntent  [message:String]  [vapid:String]
  org.unifiedpush.android.distributor.UNREGISTER
      token:String  pi:PendingIntent
  org.unifiedpush.android.distributor.MESSAGE_ACK
      token:String  id:String                (only if the incoming broadcast had id)

DISTRIBUTOR -> CONNECTOR  (broadcast, setPackage(applicationPkg), no component)
  org.unifiedpush.android.connector.NEW_ENDPOINT
      token:String  endpoint:String  [id:String]
  org.unifiedpush.android.connector.MESSAGE
      token:String  bytesMessage:byte[]  [id:String]
  org.unifiedpush.android.connector.REGISTRATION_FAILED
      token:String  [reason:String in {INTERNAL_ERROR, NETWORK, ACTION_REQUIRED, VAPID_REQUIRED}]
  org.unifiedpush.android.connector.UNREGISTERED
      token:String  [newDistributor:String]
  org.unifiedpush.android.connector.TEMP_UNAVAILABLE
      token:String  [newDistributor:String]

Every distributor->connector broadcast without a live `token`
is silently discarded by the connector.  [x] = optional extra.
```
