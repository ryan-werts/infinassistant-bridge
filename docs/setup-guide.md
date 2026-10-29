---
owner: Global
review_cadence_days: 90
last_reviewed: 2026-10-28
status: active
---

# InfinAssistant Setup Guide

End-to-end walkthrough for connecting a PineTime watch running the InfinAssistant firmware to Home Assistant.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| Home Assistant 2024.1+ | Core, OS, or Supervised. Install the integration through HACS, or with write access to its configuration directory (see Step 1). |
| Android phone (Android 12+) | BLE central; runs the InfinAssistant Bridge app (minimum SDK 31). |
| PineTime watch | Flashed with the InfinAssistant firmware (see the [firmware repository](https://github.com/ryan-werts/infinassistant-firmware)). |
| Downloads | The app and the integration come from GitHub Releases. The integration is also available through HACS (see Steps 1 and 3). |
| Network reachability | The phone must reach Home Assistant at the URL you give the app (Step 3): on the LAN, or remotely through Nabu Casa or your own reverse proxy. |

---

## Step 1: Install the HA Integration

Install it with HACS, or copy the files by hand.

**With HACS:** open HACS → **Custom repositories**, add `https://github.com/ryan-werts/infinassistant-ha` with the type **Integration**, then download **InfinAssistant**. The integration sits at the root of that repository, where HACS looks for it.

**By hand:** download the release zip from the [integration releases](https://github.com/ryan-werts/infinassistant-ha/releases) and copy its `custom_components/infinassistant` folder into `/config/custom_components/`. Use the SSH or File editor add-on, or the Samba share, to reach `/config`. On Home Assistant Core, use your configuration directory in place of `/config`.

Restart Home Assistant. To update later, install the new release (HACS update, or copy the folder again) and restart.

---

## Step 2: Configure the Integration

1. Go to **Settings** → **Devices & Services** → **Add Integration** → search **InfinAssistant**.
2. Enter a **Device ID** for the watch: its Bluetooth MAC address or a name such as `pinetime_bedroom`. It is stored lowercased, and a MAC address loses its separators (`AA:BB:CC:DD:EE:FF` becomes `aabbccddeeff`).
3. The next form, **Copy Your Webhook Credentials**, shows:
   - **Webhook ID**: 32 hexadecimal characters. The app needs it.
   - **Full URL**: `<your HA URL>/api/webhook/<webhook ID>`, for reference.
   - **HMAC Secret (base64)**: the signing key. The app needs it too.
   - **QR Payload**: the same values as one line of JSON. Despite its label it is plain text, not a QR code, and the app has no QR scanner.
4. Copy the **Webhook ID** and the **HMAC Secret** somewhere you can paste them on the phone, then submit the form to create the entry.

> The HMAC secret is shown only on this form. If you lose it, open the integration entry → **Configure**, tick **Rotate signing key**, submit and confirm: the result shows a new secret, and the old one keeps working for 60 minutes.
>
> The webhook ID stays visible in the **Configure** dialog and in the `webhook_url` attribute of any InfinAssistant sensor.
>
> When Home Assistant is reached over plain HTTP the form warns that webhook data is sent unencrypted; HTTPS is recommended.

---

## Step 3: Install the Android App

Download the APK attached to the latest release of the [bridge repository](https://github.com/ryan-werts/infinassistant-bridge/releases). Install it by opening the file on the phone (allow installs from unknown sources when Android asks), or with `adb install -r <file>.apk` from a computer.

1. Open the app and tap **Onboarding / Home Assistant**. On the **Setup** screen:
   1. **Home Assistant Connection**: enter the **Local URL** (e.g. `http://homeassistant.local:8123`; use `https://` where Home Assistant has a certificate) and, optionally, a **Remote URL**. Paste a **Long-lived access token** (Home Assistant → your profile → **Security** → **Long-lived access tokens** → **Create token**; **Open HA** opens the profile page) and tap **Save**. **Home networks** lists the subnets (for example `192.0.2.0/24`) the phone must be on for an `http://` local URL to be used, since its token goes unencrypted; elsewhere the app uses the Remote URL. **Add this network** adds the one the phone is on, and with an empty list an `http://` local URL is not used at all (the app asks you to set your home networks); an `https://` local URL is not held to the list. **Test** reports **Connection OK** when the app can reach Home Assistant.
   2. **Permissions**: tap **Grant permissions** and allow nearby devices (Bluetooth scan and connect) and notifications. The same request also asks for the microphone.

> **Network policy (FR-8).** Android's network security config permits cleartext `http://` for every host, because it cannot name an address range. The app's HTTP clients then refuse `http://` to any host that is not local. Local means:
> - IPv4: `10.x.x.x`, `127.x.x.x`, `172.16.x.x` to `172.31.x.x`, `192.168.x.x`, `169.254.x.x` (dotted quads only, no leading zeros);
> - IPv6: `::1`, link-local `fe80::/10`, unique-local `fc00::/7`;
> - names: `localhost`. A name ending in `.local` or `.home.arpa` counts as local only when every address it resolves to is in one of the ranges above (app 2.1.127; earlier builds accepted any such name).
>
> Any other address or name is refused, and the app uses the Remote URL instead. The access token and the webhook data then cross the network unencrypted, so prefer `https://` where you can. An `http://` Local URL is also used only when the phone has an address inside one of the **Home networks**; with none set, no `http://` Local URL is used. `https://` is not affected by the Home networks.

---

## Step 4: Enter the Webhook Credentials in the App

The app has no QR scanner: the two values from Step 2 are pasted in by hand.

1. In the app, open **InfinAssistant (HA Sensors)** from the home screen.
2. Turn on **Enable InfinAssistant**.
3. Paste the **Webhook ID** into **Webhook ID**: the ID only, not the full URL. The app builds the webhook URL from the Local or Remote URL set in Step 3.
4. Paste the **HMAC Secret** into **HMAC Secret**. Without it Home Assistant rejects every sensor report with HTTP 401.
5. Optionally adjust **Sensor Report Interval** (30 to 300 seconds, default 60).

The status line at the bottom of the screen names whatever is still missing. It reads **Status: Active** once the watch is paired and Connected Mode is running (Step 5).

---

## Step 5: Pair the Watch via BLE

1. Wake the watch and keep it next to the phone. It advertises by default; if Bluetooth was switched off on the watch, turn it back on under **Settings** → **Bluetooth**.
2. In the app, open **Device pairing** from the home screen (or tap **Pair watch** on the Setup screen), then tap **Add PineTime Watch**.
3. Android lists nearby devices whose name starts with *PineTime* or *InfiniTime*. Select your watch.
4. The app starts bonding. Accept Android's pairing request, and if the watch shows a six-digit code, enter it there. The screen then reports **Successfully bonded with** the watch's address, and the watch is listed as **Bonded**.
5. Start the bridge: tap **Enable Connected Mode** on the Setup screen, or switch on **Connected Mode** from the home screen. It needs the Home Assistant connection from Step 3 and a bonded watch. Its foreground service keeps the BLE link and the Home Assistant connection alive in the background.

> If bonding does not start, pair the watch from Android's Bluetooth settings and return to the app.
>
> Battery optimization on Android can break the persistent connection. Exempt the InfinAssistant app from battery optimization in the phone's battery settings.

---

## Step 6: Configure the Watch Face via the Lovelace Card

The card comes with the integration (0.3.0 and later), which serves it to your dashboards: there is nothing to copy or register.

1. Open your dashboard → **Edit** → **Add Card** → search **InfinAssistant Watch Face**. The card appears automatically.
2. In the card editor, pick your watch under **Watch Device**.
3. Configure:
   - **Screens**: Add up to 6 screens (Dashboard or Tile type). More than 4 needs watch firmware 2.4.100; the card shows a note saying so. On older firmware the layout is trimmed to the first 4 screens.
   - **Tiles**: Assign HA entities (switches, covers, lights) to tile buttons.
   - **Header**: Set time format, presence icons.
   - **Theme**: Choose background color and accent.
4. Click **Push to Watch**. The button shows **Pushed!** once Home Assistant has accepted the layout. Home Assistant then fires `infinassistant_watchface_layout_changed`, and the app, with Connected Mode running, sends the layout to the watch over BLE.

> If you have an older manual install, remove the `/local/infinassistant-watchface.js` resource (and any `/local/infinisstant-watchface.js` from an earlier install) from **Settings** → **Dashboards** → **Resources**, and delete the file from `/config/www/` if it exists. A second copy causes browser console warnings.

---

## Step 7: Verify Data Flow

After setup is complete, verify all components are working:

### Sensor entities in HA

1. Go to **Settings** → **Devices & Services** → **InfinAssistant** → click your device (named `PineTime <device_id>`).
2. You should see these entities:
   - `sensor.pinetime_<device_id>_steps` — step count
   - `sensor.pinetime_<device_id>_heart_rate` — heart rate BPM
   - `sensor.pinetime_<device_id>_battery` — battery percentage
   - `sensor.pinetime_<device_id>_motion_state` — motion state
   - `sensor.pinetime_<device_id>_heart_rate_state` — heart rate state (text; from integration 0.3.4). If the watch's device is assigned to an area, newer Home Assistant versions put the area's name at the start of a new entity's ID (for example `sensor.<area>_pinetime_<device_id>_heart_rate_state`); rename it in the entity's settings if you want it to match the others.
   - `sensor.pinetime_<device_id>_diagnostics` — webhook diagnostics
   - `binary_sensor.pinetime_<device_id>_battery_charging` — charging state
   - `binary_sensor.pinetime_<device_id>_connected` — whether sensor reports are arriving

### Entity state updates

- Sensor values update every 60 seconds by default (set by **Sensor Report Interval** in the app's **InfinAssistant (HA Sensors)** screen).
- `binary_sensor.pinetime_<device_id>_connected` is `on` while sensor reports keep arriving, and turns `off` after the integration's **connected threshold** (default 120 seconds, set under **Configure**) passes without one.

### Action invocations

1. On the watch face, press a configured tile or action button.
2. In HA, go to **Settings** → **Automations** → verify the corresponding HA action fired (e.g., `light.toggle`).

### Full data flow summary

```
PineTime ──BLE──► Android App ──webhook (HTTP POST, HMAC-signed)──► HA Integration ──► HA entities
                      ▲                                                  │
                      └──── WebSocket: layout pushes, commands ◄─────────┘
```

---

## Voice Assist

Available from firmware 2.4.99 and app 2.1.117.

### Setup

On the phone, open the InfinAssistant app and go to **Connected Mode** → **Voice Assist** toggle to enable it (this grants the microphone permission). Then pick an **Assist Pipeline** from the dropdown (Home Assistant's preferred by default).

**Pipeline requirement:** Home Assistant's Assist pipeline must have:
- Speech-to-text engine (e.g. Whisper)
- Text-to-speech engine (e.g. Piper)

If you don't have one, go to Home Assistant → **Settings** → **Voice assistants** → **Create** (or edit the existing one) and configure both.

### Usage

1. Double-click the watch's side button. The watch gives two short buzzes when the phone starts listening (the watch shows nothing; the phone does the listening, the PineTime has no microphone).
2. Speak your request. A pause of about 1.5 s ends it (15 s at most); a conversation is at most 6 turns or 3 minutes.
3. The phone speaks the answer, then listens again (two short buzzes on the watch).
4. Say "thanks", "done", or stay quiet to end the session. Or double-click again to cancel immediately.
5. With voice assist off, the double-click opens the watch's notifications as before.

### Requirements

- Firmware 2.4.99 or later (the app bundles it).
- Home Assistant reachable from the phone (same as sensor reports).
- After a phone restart, open the app once before voice works — Android does not let a background service (the BLE bridge) use the microphone at startup.

### Troubleshooting

If Home Assistant rejects the access token (expired, revoked, or invalid), voice assist refuses further requests until you re-enter the token in the app's **Setup** screen. This prevents the phone from being rate-limited or banned by repeated unauthorized attempts.

---

## Phone Notifications

Phone notifications (texts and other alerts) show on the watch as InfiniTime notifications: the popup, the vibration, and the swipe-down list. Available from app 2.1.121. Dismissing in both directions needs firmware 2.4.101; forwarding alone also works on 2.4.100.

### Turn it on

1. In the app, open **Notifications** from the home screen.
2. Switch on **Forward phone notifications** (off by default). It saves at once; the screen's **Save** button is for the Home Assistant settings below it.
3. If the app does not have notification access yet, Android opens the **Notification access** page. Allow the app there. Back on the screen, the line reads **✓ Notification access granted** (or **⚠ Notification access required**; **Grant Notification Access** opens the page again).
4. **Sideloaded APK on Android 13 and later:** the access switch may be greyed out ("Restricted setting"). Tap **App info** on the Notifications screen, tap the three dots at the top right, then **Allow restricted settings**, and go back to step 3.
5. Check the **Watch link** line (see below).
6. Tap **Send a test**. The app posts a "Forwarding test" notification on the phone and it reaches the watch. Swipe it away on the watch, and it is cleared on the phone. On Android 13 and later the app first asks for permission to post notifications. The button is enabled only when the switch is on, access is granted, and the link allows forwarding.

### Pairing and the watch link

Notification content is private, so the app sends phone notifications only to a paired watch over an encrypted link. The screen's link line shows the state:

| Line | Meaning | Forwarded |
|------|---------|-----------|
| **✓ Watch link: paired and encrypted** | Bonded, and the watch (2.4.101+) reports the link encrypted | Yes |
| **✓ Watch link: paired** | Bonded; the watch's firmware (2.4.100) does not report encryption | Yes |
| **Watch link: paired, checking with the watch** | Bonded; this connection's HELLO has not arrived yet | Held |
| **⚠ Watch link: paired, not encrypted yet** | Bonded, but the watch reports the link not encrypted | Held |
| **⚠ Watch link: the phone and the watch disagree about the pairing** | Still not encrypted after about a minute: one side has lost the bond | Held |
| **⚠ Watch link: not paired** | No bond with the watch | No |

- **Not paired:** tap **Pair with watch**. Android asks for the 6-digit code shown on the watch.
- **Disagree:** the screen offers **Open Bluetooth settings**. Forget the watch there, then tap **Pair with watch**; the watch replaces its old bond.
- **Encryption on reconnect:** from firmware 2.4.101 a bonded watch asks for encryption on every connection by itself.
- **Held notifications:** while the link is checking or not encrypted, the newest 5 are kept and sent once it is encrypted, unless they leave the phone first.
- **Not paired:** a notification posted then is skipped, not saved.

### What is forwarded

A notification is forwarded only when all of these hold:
- the switch is on, and it is from another app (the test is the one exception);
- it is not ongoing (music, navigation), not a foreground service's, and not a group summary;
- it is not silent (importance at least Default);
- the phone's Do Not Disturb would show it;
- it has a title or text;
- for one already forwarded, what the watch would show has changed, and it is not an update that asks to alert only once.

There is no per-app list: an app you made silent in Android's settings is not forwarded. For a messaging app the watch shows the newest message, with the sender as the title ("conversation · sender" in a group). Otherwise the title is the notification's title (or the app's name) and the preview is its long text or its text. Home Assistant notifications are not affected by this switch.

### Dismiss both ways (firmware 2.4.101)

- **On the phone:** a notification read, opened or cleared on the phone leaves the watch, silently. If the watch is out of range, it leaves when the watch reconnects.
- **On the watch:** swipe a phone notification away in the list or the preview, and it is cleared on the phone. A notification the phone does not let you clear (not clearable) stays on the phone. If the link is down at that moment, it stays on the phone.
- **Several at once (firmware 2.4.102):** swiping the pop-up away shows the next waiting notification, and the screen then works as the list (swipe up and down to move, right to dismiss). It goes back to the watch face when none are left. On 2.4.101 and earlier, swiping the pop-up away always went back to the watch face; the rest were still in the swipe-down list.
- **Updates:** a re-posted notification replaces its entry on the watch instead of adding a second one.
- On firmware 2.4.100, forwarding works but none of the above: each update adds an entry, and dismissing on either side does not reach the other.

### Limits

- The watch keeps InfiniTime's last 5 notifications; a sixth pushes out the oldest.
- The app cuts the title to 47 bytes and the preview to 95 bytes. The watch fits title and preview together into 100 bytes, so a long title shortens the preview. Letters outside basic Latin take 2 to 4 bytes each.
- Emoji are not drawn: the watch font has none.
- After a reconnect, only phone notifications still on the phone are sent again, the newest 5 at most.

---

## Watch Brightness

The brightness button in Quick Settings cycles Low, Medium, High and B (firmware 2.4.105), then back to Low. The brightness icon with a small B means the B setting is on. On B, a side-button press lights the screen at High and every other wake (notification, alarm, charging, Find Watch, tap or raise) lights it at Low. The screen keeps its level when it dims before sleep and when it comes back after a touch. A watch on firmware older than 2.4.105 shows a stored B as High.

---

## Next Steps

- [BLE Protocol Specification](https://github.com/ryan-werts/infinassistant-firmware) — the message-level reference, in `infinassistant/docs/ble-protocol-spec.md`
- [Architecture Overview](https://github.com/ryan-werts/infinassistant-firmware) — system design, in `infinassistant/docs/architecture.md`
