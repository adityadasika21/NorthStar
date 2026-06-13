# Northstar

**Low-power motorcycle navigation for the Royal Enfield Tripper Dash — with the phone screen off.**

Northstar is a personal Android companion app for a **Royal Enfield Himalayan 450**. It projects turn-by-turn navigation onto the bike's round **Tripper TFT dash** without cooking the phone in your tank bag.

---

## Why

The official Royal Enfield app mirrors Google Maps to the dash by **screen-projection** — it keeps the phone's OLED lit and streams what's on screen. On a long ride in the sun that overheats the phone and drains the battery fast.

Northstar takes a different approach:

> It renders the map **off-screen**, hardware-encodes it to **H.264**, and streams it to the dash over the bike's WiFi — so the phone screen can stay **completely OFF** the entire ride.

That single architectural difference is the whole point of the project.

---

## Features

- **🧭 Navigation** — share a destination from Google Maps, preview the road route, send it to the dash. Turn-by-turn distance + ETA on the dash's native widgets, with off-route **rerouting**.
- **🗺️ Real Google Maps tiles** rendered to the round dash, heading-up rotation, joystick pan/zoom via the bike's physical control, and an **exit-to-free-roam** mode.
- **🔌 One-tap connect** — programmatic WiFi join + auth handshake + stream, with auto-connect on app open and auto-reconnect on link loss.
- **🛠️ Garage** — maintenance log (chain, oil, filters, brakes, coolant) with interval tracking and due/overdue reminders, plus a fuel diary with automatic mileage (km/l), efficiency trends, and cost tracking. Backed by on-device SQLite.
- **🔋 Built for endurance** — hardware H.264 encode at low bitrate, frame caching, WiFi/wake locks, and thermal back-off, all so the screen-off ride stays cool.

---

## How it works

```
Google Maps share ─▶ route (OSRM) ─▶ off-screen map render (Canvas)
                                              │
                                   MediaCodec H.264 (hardware)
                                              │
                                     RTP over UDP :5000
                                              ▼
                                    Royal Enfield Tripper Dash
        (K1G control plane over UDP broadcast :2000  ·  RSA-1024 + AES-256 auth)
```

The dash speaks an undocumented binary protocol ("K1G"): a stateful RSA/AES handshake, then it decodes an H.264/RTP stream over UDP. Northstar implements the control plane and auth in Kotlin and feeds the dash a map it renders itself — the dash doesn't care what produces the video.

The control-plane work builds on the excellent reverse-engineering in [**better-dash**](https://github.com/norbertFeron/better-dash) as a reference.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** (native Android)
- **MediaCodec** hardware H.264 encode → custom RTP packetizer → UDP
- **OSRM** for road routing; raster map tiles rendered off-screen with `Canvas`
- **SQLite** (on-device source of truth) for the Garage
- **Firebase** auth (Google Sign-In); Firestore sync planned

---

## Status

Actively built and tested against a real Tripper Dash (firmware 11.63). The navigation core — connect, stream, route, reroute, free-roam — works end-to-end. The Garage (maintenance + fuel) is functional and persisted.

**Roadmap:** Firestore multi-device sync · ride recording & history · TTS voice overlay · media-now-playing overlay.

---

## Disclaimer

This is a **personal project, built for one bike and one dash target**. It is **not affiliated with, endorsed by, or supported by Royal Enfield**. The dash protocol is reverse-engineered and unofficial; use at your own risk. Not a product — just a better ride for me.
