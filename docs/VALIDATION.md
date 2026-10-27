# Validation evidence (summary)

Raw evidence (logs, JSON receipts, worlds) stays in the developers' private archive; this page summarizes it honestly.
All desktop results: Windows 11, Temurin 25.0.4.1, 16-thread desktop CPU (Ryzen 7 9800X3D class), offline servers.

## 0.2.0-beta.1 (this release)
| Check | Result |
|---|---|
| Unit suite on the release commit (`build --rerun-tasks`, fixtures present) | **PASS** 2060 tests, 0 failures, 2 skipped (environment-gated) |
| Release jar | `phoenium-0.2.0-beta.1.jar`, 2,500,473 B, SHA-256 `09530B8BA5FC090E584D4419CE133AD647CE6D6FDB708D82B0B2ADE76765037B` |
| P0 fix unit tests (`RegionalCustomSpawnerBoundaryTest`: terrain still loading ends only that attempt; every other failure still fails) | **PASS** (red first, then green) |
| P0 desktop reproduction (one player teleported 400 blocks every 4 s for 10 min, default mode) | Server stayed up, save and restart OK, no crash. **The timing window was not hit** on the fast desktop, so this shows no regression only. |
| Desktop gate chain re-run on this exact jar (Step B, A3, determinism, protections, transitions r10, save/restart, memory cycle) | **IN PROGRESS** at publication time; results will be added here. |

## RC1 "D45" (identical except the P0 fix and version metadata)
| Gate | Result |
|---|---|
| Build + suite | PASS 2058 tests, 0 failed, 2 skipped |
| Step B startup/lifecycle, engine off (2 worker widths x 2 arms) | PASS 4/4 |
| A3 mob decisions on/off, serial and parallel gate | PASS (both PARALLEL) |
| Determinism: 160 entities, 1/2/5 workers, full NBT diff | PASS, 0 differences |
| Protections (projectile/pearl caps, off-thread counters) | PASS 20/20 |
| Transitions, original fixture | 4/10 intermittent: vanilla portal re-entry; the previous candidate fails the same way |
| Transitions, hardened fixture r10 (same checks) | PASS 6/6 (3 runs each on RC1 and its predecessor, 40/40 checks) |
| Save/restart + unload/reload integrity + region scan | PASS. Bit-equality inconclusive (world generation of this stack is nondeterministic) |
| Memory cycle (35 Java + 15 Bedrock bots, 4 GB) | PASS, post-GC 446 / 485 / 504 MB |
| Performance A/B vs previous candidate, 16 bots, ABAB | PASS, no regression (8/8) |
| GC pauses, 32 players exploring, 4 GB | ACCEPTED as beta limitation (not resolved) |
| Bedrock startup smoke / protocol (client 26.52, protocol 2193) | PASS (startup and protocol only) |
| Bedrock real-client checklist | NOT RUN |
| Legacy Java via Via, two-client voice | NOT RUN |

## Optional Fabric compatibility (Stage 11, desktop)
| Stack | Result | Gate decision |
|---|---|---|
| EconomyCraft 1.11.0 | 28/28 on every arm | PARALLEL |
| burger-economy 1.1.0 | 25/25 | SERIAL |
| SMP stack: EconomyCraft + LuckPerms + Essential Commands + OPAC + Universal Graves + Ledger + VillagerBased PlayerShops + Simple Voice Chat | 23/23 | SERIAL |

## Android (per device, HARDWARE VALIDATION REQUIRED for anything else)
| Check | Result |
|---|---|
| Phoenix release-config app, default mode, 45 min on one phone, heap auto 636 MB | PASS (same JVM pid, RSS peak 995 MB) |
| First run, upgrade, start/stop, force-stop recovery, world preservation | PASS |
| Engine mode on phones | Measured only with debug test builds; no release claim |
