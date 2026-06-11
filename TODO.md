# Northstar — TODO

Full backlog after a complete code read (2026-06-12). Priority order is fixed:
**Navigation → Power efficiency → Maps↔Northstar↔Dash integration → correctness →
persistence/sync → secondary features → Garage (last).** Garage is intentionally
the lowest priority.

Legend: `[P0]` do first · `[P1]` … · `[P5]` last. Checkboxes track completion.

---

## P0 — NAVIGATION (primary purpose)

Current state: the dash shows the map with a rider dot, destination pin, and a
**straight-line ("crow-flies") gold line** — there is no real routing. RouteScreen
stats ("218 km / 4:50 hrs / 13:32 ETA") and DashScreen "next turn 400 m" are
hardcoded. `MapRenderer` is always north-up.

- [ ] **Real routing engine (road-following route, not a straight line).**
  Pick offline-capable routing (Valhalla/GraphHopper/OSRM tiles, or MapLibre +
  offline routing). Compute the actual road polyline from rider → destination and
  draw THAT in `MapRenderer` instead of `riderLat→destLat` line.
- [ ] **Turn-by-turn instructions to the dash.** Port better-dash
  `build_active_nav_packet` (nav-info TLVs: maneuver icon + distance-to-next +
  total distance, ~1 Hz). Wire into `DashSession` after `startStreaming`.
  Replace hardcoded "next turn 400 m" with real maneuver/distance.
- [ ] **Real distance + ETA.** Replace `Mercator.haversineKm` straight-line value
  with road distance and a moving-average-speed ETA. Surface in RouteScreen
  (kill the hardcoded 218 km / 4:50 / 13:32) and DashScreen info strip.
- [ ] **Heading-up map option.** Riders expect the map to rotate to travel
  direction. Add rotation in `MapRenderer.draw` driven by GPS bearing; make
  north-up vs heading-up a setting.
- [ ] **Verify joystick code→action mapping on the bike.** `DashCommands.BTN_05..22`
  → pan directions are guesses (`DashViewModel.onButton`). Log real codes on the
  dash, then map pan/zoom/recenter correctly.
- [ ] **Pan/zoom correctness.** `panX/panY` accumulate in pixels and don't rescale
  when zoom changes; recenter resets pan but the map re-centres on GPS each frame
  so manual pan fights the follow. Define "follow mode" vs "manual pan mode"
  explicitly (the dash joystick should enter manual pan, auto-recenter after idle).
- [ ] **GPS robustness.** `LocationTracker` uses GPS_PROVIDER + NETWORK fallback;
  add FUSED provider, handle no-fix gracefully on the dash (don't freeze), and
  show "acquiring GPS" state instead of centering on 0,0.

## P0 — POWER EFFICIENCY / THERMALS (the reason the app exists)

The whole point is "screen off, phone stays cool." Current render loop re-decodes
tiles and re-runs a `ColorMatrix` filter on every tile every frame at 4 fps —
wasteful. Measure and cut.

- [ ] **Cache the rendered frame; only redraw on change.** In `DashViewModel`
  stream loop + `MapRenderer`: skip re-rendering when neither position, pan, zoom,
  nor nav info changed. Re-encode the previous frame (or hold) instead of redrawing.
- [ ] **Pre-filter tiles once, not per-frame.** `MapRenderer.tilePaint` applies an
  invert+desaturate+dim `ColorMatrix` on every tile draw. Apply the dark filter
  once at tile-load time in `TileProvider` and cache the darkened bitmap.
- [ ] **Confirm the encoder is the HARDWARE AVC codec.** `MediaCodec.createEncoderByType`
  can return a software encoder (CPU-hot). Enumerate codecs, pick a hardware AVC
  encoder explicitly; log which one is chosen.
- [ ] **Kill per-frame allocations.** `MapRenderer` allocates `RectF`/`Path` per
  tile per frame → GC churn. Reuse objects.
- [ ] **Audit wake/wifi locks.** `DashKeepAliveService` holds PARTIAL_WAKE_LOCK +
  `WIFI_MODE_FULL_LOW_LATENCY`. LOW_LATENCY is power-hungry — measure vs
  HIGH_PERF; ensure both release the instant streaming stops (verify `onDestroy`).
- [ ] **Adaptive behaviour when stationary.** When the bike isn't moving, drop the
  effective redraw rate (still send projection heartbeat) to save power; resume on
  movement.
- [ ] **Thermal + battery instrumentation.** Read `PowerManager.thermalStatus`,
  back off (lower fps/bitrate) if it climbs. Measure a real ride with
  `dumpsys batterystats` and sustained temperature; record numbers here.
- [ ] **Bound tile network.** `TileProvider` cellular fetches should be rate-limited
  and fully cache-first; avoid hot fetch loops when offline.

## P1 — GOOGLE MAPS ↔ NORTHSTAR ↔ DASH (perfect end-to-end integration)

Share a destination in Google Maps → it lands in Northstar → routes → shows on the
dash. The plumbing exists but is unproven and partial.

- [ ] **Validate the share intent on-device for every real format.** `LocationParser`
  handles `@lat,lng`, `?q=`, `?ll=`, `/place/Name`. Real Google Maps shares are
  usually `https://maps.app.goo.gl/...` short links (need redirect expansion —
  implemented but untested) and sometimes `geo:` / plus-codes. Test each from the
  actual Maps app and fix gaps.
- [ ] **Reliable place name + coords.** After expansion, extraction is best-effort;
  confirm name and lat/lng come through for shared pins, businesses, and dropped
  pins. Fall back gracefully.
- [ ] **Hand the real destination to routing + dash.** `setDestination` already
  prefetches tiles and pushes a route card; once routing exists (P0), feed the
  shared destination into the router and the turn-by-turn pipeline so the dash
  shows the real route, not just a pin + straight line.
- [ ] **Prefetch coverage for the whole route.** `TileProvider.prefetch` covers a
  radius + a straight corridor; switch to prefetching along the actual route
  polyline so offline riding has tiles the whole way.
- [ ] **Route preview screen reflects reality.** `RouteScreen` shows the parsed name
  but still renders a hardcoded canvas route + fake stats; draw the real route and
  real distance/ETA, and make "Send to Dash" carry the routed path.

## P2 — CONNECTION STATE & CORRECTNESS

- [ ] **Unify connection status (Home says "Connected", Dash says "not").**
  `AppViewModel.conn` is a manual flag defaulting to `Connected`, independent of
  the real `DashViewModel.stage`. Home/Settings must reflect the actual dash
  session. Remove the duplicate source of truth.
- [ ] **Home screen live data.** Hero card hardcodes "RE-HIM-450 / 2.4 GHz / GPS
  Strong / Phone 74%". Wire to real connection, GPS, and battery; or remove.
- [ ] **Bottom nav: selected icon sits lower than the rest.** `NorthstarBottomNav`
  in `AppNavigation.kt` adds the active indicator dot inside the icon `Box`,
  shifting the active column. Give every tab a fixed-height icon slot.
- [ ] **Recent destinations are hardcoded** (Chitkul, Jalori) — populate from real
  shared/visited history once persistence exists (P3).

## P3 — PERSISTENCE & SYNC (foundation; CLAUDE.md promises this, none exists)

There is currently **no database and no sync** — every list is static mock data and
the action buttons are no-ops. CLAUDE.md specifies SQLite source-of-truth + Firebase.

- [ ] **Add local persistence (Room/SQLite).** Schema for: maintenance log, fuel
  fill-ups, ride history, saved/recent destinations, settings.
- [ ] **Firebase sync (Firestore) on top of local DB** so a second device restores
  data. Auth already works (Google) — key data by uid.
- [ ] **Make the action buttons real.** "Mark done today", "Log a service",
  "Add fill-up", "Remind" are all `onClick = {}` today.

## P4 — SECONDARY RIDE FEATURES

- [ ] **Telemetry / ride recording.** Actually record GPS tracks during a ride
  (distance, duration, avg speed, map snapshot) and persist; RidesScreen is 100%
  static mock right now.
- [ ] **TTS / voice overlay (off / chime / full).** RouteScreen has the segmented
  toggle UI but there is no `TextToSpeech` engine wired. Implement per-trip voice.
- [ ] **Media controls overlay.** Now-playing rendered into our own video frame
  (display + reject calls only, per Android limits). Not started.

## P5 — GARAGE (LOWEST PRIORITY, do last)

- [ ] **Maintenance log** — currently static `MaintenanceTab` mock; back it with the
  P3 database, real intervals, and reminders.
- [ ] **Fuel diary** — currently static `FuelTab` mock; real fill-ups, computed
  mileage/efficiency, cost tracking, persisted + synced.

---

## Cross-cutting / housekeeping

- [ ] Google Sign-In OAuth: confirm the Android OAuth client (package + SHA-1
  `80:30:BD:71:…`) is registered in GCP console — `UNREGISTERED_ON_API_CONSOLE`
  was the cause of "[16] reauth failed". Console-side, defer.
- [ ] Verify screen-off streaming on the bike end-to-end (foreground service +
  locks landed; needs a real ride to confirm it holds and stays cool).

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
