# Northstar — TODO

Full backlog after a complete code read (2026-06-12). Priority order is fixed:
**Navigation → Power efficiency → Maps↔Northstar↔Dash integration → correctness →
persistence/sync → secondary features → Garage (last).** Garage is intentionally
the lowest priority.

Legend: `[P0]` do first · `[P1]` … · `[P5]` last. Checkboxes track completion.

---

## P0 — NAVIGATION (primary purpose) — IMPLEMENTED, needs on-bike verification

Built a full routing + nav engine: `dash/nav/{GeoPoint,PolylineCodec,Route,Router,
NavEngine}`. Router uses the public OSRM demo server (fetched at planning time while
online, cached for offline riding). NavEngine snaps GPS to the route and computes
distance-to-turn, remaining, ETA, off-route.

- [x] **Real routing engine (road-following route).** `Router` → OSRM driving route,
  decoded polyline drawn by `MapRenderer` (route polyline + casing), not a straight line.
- [x] **Turn-by-turn instructions to the dash.** `DashCommands.activeNavPacket`
  (ported from better-dash) sent at 1 Hz via `DashSession.launchNavInfo`; fed by
  `NavEngine` maneuver + distance.
- [x] **Real distance + ETA.** `NavEngine` road distance + speed-based ETA; surfaced
  in RouteScreen (real km / duration / ETA) and the DashScreen info strip + next-turn banner.
- [x] **Heading-up map option.** `MapRenderer` rotates to GPS bearing; toggle in DashScreen.
- [x] **Pan/zoom follow vs manual mode.** `DashViewModel` manual-pan switches off
  follow, auto-recenters after 8 s idle; recenter button + joystick recenter.
- [x] **GPS robustness.** `LocationTracker` GPS + NETWORK providers, prefers GPS,
  "waiting for GPS" standby frame instead of 0,0.
- [x] **Joystick code→direction mapping verified on bike (fw 11.63).** UP=0x06,
  DOWN=0x07, LEFT=0x0A, RIGHT=0x09, IN=0x18, BACK=0x12. Mapped: RIGHT=zoom in,
  LEFT=zoom out. No exit gesture (media section is for media).
- [ ] **Joystick zoom WITHOUT entering media mode.** The dash only forwards the
  joystick (09 00) to the phone while in media-control mode; in the plain
  projected-map view it keeps the joystick for its own menus. The official RE app
  apparently gets map control in nav view — capture the official app's session
  with Wireshark/tcpdump (user offered to do all regular actions while capturing)
  and find the packet/flag that enables joystick forwarding during projection.
- [ ] **VERIFY ON BIKE: maneuver glyph codes.** Only CONTINUE (0x0B) is confirmed;
  `Maneuver.dashCode` returns 0x0B for all turns. Capture the dash's other glyph
  bytes and map turn-left/right/etc. (distances are already correct).

## P0 — POWER EFFICIENCY / THERMALS — IMPLEMENTED, needs ride measurement

- [x] **Frame cache; redraw only on change.** `DashViewModel.tick` builds a frame
  signature; identical frames blit the cached bitmap instead of re-rendering
  (handles stationary automatically). Forced refresh every 2 s.
- [x] **Pre-filter tiles once.** `TileProvider.darken` applies invert+desaturate+dim
  at load and caches the dark bitmap; `MapRenderer` draws plain (no per-frame ColorMatrix).
- [x] **Explicit hardware AVC encoder.** `DashEncoder.selectHardwareEncoder` picks a
  hardware-accelerated, non-software encoder; logs the chosen codec.
- [x] **No per-frame allocations.** `MapRenderer` reuses `Path`/`RectF`/`Rect`/`Paint`.
- [x] **WiFi lock tuned.** Switched `LOW_LATENCY` → `HIGH_PERF` (keeps link up at
  lower power); wake + wifi locks released in `onDestroy`.
- [x] **Thermal back-off hook.** `DashViewModel.updateThermal` reads
  `PowerManager.currentThermalStatus`, surfaces OK/Warm/Hot chip on DashScreen.
- [x] **Bounded, cache-first tile network.** `TileProvider` rate-limits fetches
  (min gap) and is fully cache-first; route prefetch along the polyline.
- [ ] **MEASURE ON RIDE.** Capture `dumpsys batterystats` + sustained temperature
  during a real screen-off ride; tune fps/bitrate back-off thresholds. Record here.

## P1a — MAP LOOK = GOOGLE MAPS (hybrid) — IMPLEMENTED, needs your API key

Decision: real Google Maps in the phone app (free native SDK), power-efficient
Google-Maps-styled map streamed to the dash.

- [x] **In-app Dash view = real Google Maps.** Replaced the decorative `CircularDash`
  (which drew a fake random polyline) with `NorthstarMap` (maps-compose `GoogleMap`)
  clipped to the round Tripper shape: blue location dot, follows current location,
  default Google view when no destination, route polyline + marker when navigating.
- [x] **Streamed dash restyled to Google-Maps look.** `TileProvider` no longer
  inverts tiles to dark — uses standard light map tiles (also the cheapest path);
  `MapRenderer` background + standby text adjusted for the light look.
- [ ] **ADD YOUR MAPS API KEY** to `local.properties` → `MAPS_API_KEY=AIza…`.
  In Google Cloud (same project as Firebase): enable **Maps SDK for Android**,
  create an API key, restrict to package `com.northstar.app` + SHA-1
  `80:30:BD:71:…:2D:C8`. Native map display is free/unlimited (billing must be
  enabled on the project but the map view itself is never charged). Until the key
  is set, the in-app map shows blank/gray.
- [ ] Optional: give the streamed dash a night style (dark map) as a P4 setting —
  light is the Google-Maps default; dark is friendlier at night / on OLED.

## P1 — GOOGLE MAPS ↔ NORTHSTAR ↔ DASH — IMPLEMENTED, needs on-device validation

- [x] **Destination → routing → dash.** `setDestination` fetches the OSRM route,
  prefetches tiles along the polyline, and feeds NavEngine + the dash nav-info loop.
- [x] **Prefetch along the real route.** `TileProvider.prefetchRoute` walks the
  polyline (not just a corridor) so offline riding has coverage the whole way.
- [x] **Route preview reflects reality.** `RouteScreen` draws the real OSRM polyline
  (bounding-box projection) and shows real distance / duration / ETA; "Send to Dash"
  carries the routed destination.
- [ ] **VALIDATE share formats on-device.** `LocationParser` handles `@lat,lng`,
  `?q=`, `?ll=`, `/place/`, and `maps.app.goo.gl` expansion — test each real share
  type from the Maps app (pins, businesses, dropped pins, `geo:`, plus-codes) and
  fix any gaps. This is the last unproven link in the chain.

### P1b — DASH → APP TELEMETRY SYNC (bidirectional integration)

The Tripper dash sends data to the phone on UDP/2002 (the same RX path we already
parse for buttons + IDR acks). Bring the bike's live data into Northstar so the
app stays in sync with the dash.

- [~] **Decode the dash's telemetry segments.** Confirmed vs better-dash: the bike's
  instrument data arrives as **type-0F segments, AES-256-CBC encrypted** under the
  session key (better-dash only logs them as ciphertext; `decode_ic_to_app_segments`
  is just a generic slicer — it never decrypts). DONE: `DashSession.dispatchIncoming`
  now AES-decrypts 0F (IV = first 16 B) with `DashAuth.sessionKey` and logs the
  PLAINTEXT ("DASH TELEMETRY 0F … dec=…"). Since 0F rides our own session, plain
  `adb logcat -s DashSession` over USB captures it — **no root, no monitor mode**.
  REMAINING: connect to the bike, read the dash's own trip/odo/fuel/temp values off
  its screen, correlate against the logged plaintext bytes to map the fields, then
  build the typed struct. (If `dec=` shows garbage, the 0F payload framing differs —
  the raw `enc=` is logged too, so we can adjust the IV/offset.)
- [ ] **Expose a live `BikeTelemetry` state** (trip A/B, odometer, speed, fuel,
  temps, etc.) from `DashSession` → `DashViewModel`, shown on Home/Dash and usable
  by the ride recorder and fuel/maintenance auto-fill.
- [ ] **Persist + sync the dash data** (depends on P3 DB/Firestore) so trip and
  odometer history are kept and restored across devices — keeps app ⇄ dash in sync.
- [ ] **Use real odometer for maintenance intervals** (P5 Garage) instead of the
  hardcoded "14,280 km" once telemetry is flowing.

## P2 — CONNECTION STATE & CORRECTNESS

- [x] **Unify connection status (Home says "Connected", Dash says "not").**
  `AppNavigation` now derives `conn` from the real `DashViewModel.ui.stage`
  (STREAMING→Connected, WIFI/AUTH→Searching, else Offline) for Home + Settings,
  instead of `AppViewModel.conn`'s hardcoded `Connected`.
- [ ] **Home screen live data.** Hero card hardcodes "RE-HIM-450 / 2.4 GHz / GPS
  Strong / Phone 74%". Wire to real connection, GPS, and battery; or remove.
- [x] **Bottom nav: selected icon sits lower than the rest.** Fixed — icon slot is
  now a fixed 29 dp height with the icon top-aligned and the dot bottom-aligned.
- [ ] **Recent destinations are hardcoded** (Chitkul, Jalori) — populate from real
  shared/visited history once persistence exists (P3).

## P3 — PERSISTENCE & SYNC (foundation; CLAUDE.md promises this, none exists)

There is currently **no database and no sync** — every list is static mock data and
the action buttons are no-ops. CLAUDE.md specifies SQLite source-of-truth + Firebase.

- [x] **Local persistence (SQLite).** `data/NorthstarDb` (hand-rolled
  `SQLiteOpenHelper`, no Room/KSP — avoids version risk on this Kotlin 2.2.10/AGP 9
  toolchain). Tables: `fuel_fillup`, `maintenance_item`, `bike_state` (odometer).
  Still TODO: ride history + recent destinations tables.
- [ ] **Firebase sync (Firestore) on top of local DB** so a second device restores
  data. Auth already works (Google) — key data by uid.
- [x] **Garage action buttons are real.** "Mark done today", "Log a service",
  "Add interval", "Add fill-up", "Set odometer" all persist via `GarageViewModel`.
  (RouteScreen "Remind" / TTS still pending under P4.)

## P4 — SECONDARY RIDE FEATURES

- [ ] **Telemetry / ride recording.** Actually record GPS tracks during a ride
  (distance, duration, avg speed, map snapshot) and persist; RidesScreen is 100%
  static mock right now.
- [ ] **TTS / voice overlay (off / chime / full).** RouteScreen has the segmented
  toggle UI but there is no `TextToSpeech` engine wired. Implement per-trip voice.
- [ ] **Media controls overlay.** Now-playing rendered into our own video frame
  (display + reject calls only, per Android limits). Not started.

## P5 — GARAGE (LOWEST PRIORITY, do last)

- [x] **Maintenance log** — DB-backed `MaintenanceTab`: seeded intervals, "most
  urgent" hero, per-item due/overdue from the real odometer, mark-done + add/delete
  interval. (Reminders/notifications still pending.)
- [x] **Fuel diary** — DB-backed `FuelTab`: add/delete fill-ups, computed per-fill
  km/l (odometer-gap), 30-day avg efficiency + spend + litres, bar chart from real
  data. (Firestore sync still pending.)

---

## Cross-cutting / housekeeping

- [ ] Google Sign-In OAuth: confirm the Android OAuth client (package + SHA-1
  `80:30:BD:71:…`) is registered in GCP console — `UNREGISTERED_ON_API_CONSOLE`
  was the cause of "[16] reauth failed". Console-side, defer.
- [ ] Verify screen-off streaming on the bike end-to-end (foreground service +
  locks landed; needs a real ride to confirm it holds and stays cool).
- [ ] **Capture the official RE app with Wireshark** (user offered): joystick
  forwarding in nav view, time-sync cadence, telemetry decode (P1b), exact
  nav-info glyph codes. One good capture answers all four — do this next ride.

## Done

- [x] Dash control-plane protocol corrected vs better-dash (broadcast TX, offset-8
  parse, stateful RSA auth, nav-entry order). Dash connects + shows the map.
- [x] Programmatic WiFi join to `RE_P0RP_260525` (pwd `12345678`) + auto-reconnect.
- [x] Foreground service + wake/wifi locks so streaming survives screen-off.
- [x] OSM map renderer into the hardware H.264 encoder (tiles, rider dot, dest pin,
  straight-line bearing, distance banner).
- [x] Google Maps share intent → parse → RouteScreen → Send to Dash (plumbing;
  needs P1 validation).
- [x] Single-button connect flow (WiFi → auth → nav → stream).
- [x] **GPS no longer freezes when the screen goes off** — foreground service now
  declares the `location` FGS type (Android 14+ cuts location to backgrounded
  apps without it; rider position stuck at first fix).
- [x] **Frame loop survives encoder errors** — 4 fps loop catches exceptions and
  rebuilds the MediaCodec encoder after 3 consecutive failures, instead of dying
  silently while heartbeats keep the session "connected" (map frozen, nav stopped).
- [x] **Dash clock fed real time** — replaced better-dash's hardcoded capture time
  (14:51:52, sent once) with a dynamic 06 06 time-sync at connect + every 30 s.
- [x] **Smoother map (fixed "cheap"/laggy feel)** — encoder 4→8 fps (+bitrate
  204k→327k), and `DashViewModel.tick` now eases a smoothed camera toward each GPS
  fix (0.35/frame) so motion glides between 1 Hz updates instead of stepping; sig
  resolution tightened to ~1 m so each eased step redraws.
- [x] **Nicer basemap** — swapped OSM-standard tiles for CartoDB **Voyager**
  (cleaner, Google-Maps-like); cache dir bumped to `tiles_voyager` to drop the old
  style; standby bg matched to Voyager land colour.
- [x] **App icon** — Northstar compass star (`~/Downloads/Northstar.png`) wired as
  adaptive (star foreground + #080809 bg) + legacy square/round at all densities.
- [x] **Share fixed (singleTask)** — MainActivity `singleTop`→`singleTask`, so a Maps
  share routes into the one existing (connected) instance instead of a new "not
  connected" task. Default zoom 19 / max 20. "7.9 km" template default → 0.
- [x] **Share works while connected to the dash** — replaced `bindProcessToNetwork`
  (which starved the whole app of internet on the no-internet dash WiFi) with
  per-socket binding (`Network.bindSocket` on the dash UDP sockets only). Routing,
  geocoding, tile fetch now use cellular while the dash sockets use the dash WiFi.
- [x] **Off-route rerouting** — `DashViewModel.maybeReroute`: >4 s off the line →
  Router recomputes from live GPS to the destination (12 s cooldown). Enabled by the
  per-socket internet fix above.
- [x] **Map-matching** — rider snapped onto the route within 25 m (kills GPS
  lane/road jitter), raw GPS shown when genuinely off-route so reroute still fires.
- [x] **Stationary GPS drift fixed** — `requestLocationUpdates` minDistance 2 m→0, so
  GPS keeps reporting when parked and a coarse NETWORK fix can't take over and wander.
  Plus GPS-preference + impossible-jump rejection in `LocationTracker`.
- [x] **Real Google Maps tiles** on the dash (distinct buildings/roads/colours) —
  replaced washed-out Voyager; gentle saturation only (over-processing flattened it).
- [x] **Exit navigation → free roam** button on the Dash screen.
- [x] **Auto-connect** on opening the Dash screen (no manual Connect tap each ride).
- [x] **0F telemetry decrypt logging** — but live capture showed the dash actually
  streams **plaintext `0x0C`** fields; with ignition on they read ~0 (ODO/trip/fuel
  all zero vs the dash's own 324 km), so the dash does NOT forward instrument data to
  a projection client. Telemetry-into-app is therefore not feasible over this link.

## Pending (needs the phone reconnected)

- [ ] **Kill the duplicate app entries + work-profile install.** Likely an old
  `com.example.northstar` build (default id, pre-`com.northstar.app`) still on the
  device → two icons. When the phone's back: `adb uninstall com.example.northstar`,
  `adb uninstall com.northstar.app`, then reinstall to the personal user only:
  `adb install --user 0 app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Consider Voyager **@2x** tiles for extra crispness on the dash — deferred:
  512 px tiles are ~4× memory, would need the LruCache (120) cut to ~40 to be safe.
