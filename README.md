# MeshNet Core

Pure-Java mesh engine + simulator. Package `com.herabarman.meshnet`.

## Modules
- `core`  – the engine (Java 11, no Android imports): Packet, DedupCache, MeshNode, GatewayManager,
            ZoneRouter, StoreAndForward, Crypto (AES-GCM, X25519), Transport / ServerBridge / Clock interfaces
- `sim`   – virtual-clock simulator, 1k to 100k+ nodes, churn, gateway failure, metrics, CSV export
- `dashboard` – live browser dashboard on top of the simulator (built-in JDK HTTP server + one HTML page, no dependencies)
- `android-adapter` – stub showing what BluetoothTransport has to implement

## Topology
1. Zone = P2P cluster (8-20 nodes, ring + random chords). Flooding only inside the zone (TTL + dedup).
2. **Full zone mesh (default up to 64 zones):** every zone has direct offline links to every other zone,
   `linksPerPair` (default 2) links per pair, each between different border nodes, so the load is spread
   and no single user going offline disconnects two zones. A dead border node is replaced by another
   member after the election delay. For N zones that is N(N-1)/2 zone pairs, so it only fits small and
   medium networks.
3. **Ring + shortcuts (automatic above 64 zones):** zones sit on a ring with links at +-1, +-2, +-4 ...,
   1-2 gateways per zone (elected by battery + signal + internet), greedy routing, about log2(zones) hops.
   This is what makes 1,000,000 nodes possible.
4. **Three delivery modes** (`DeliveryMode`, same engine, one switch):
   - `OFFLINE_ONLY`: the server is never used.
   - `OFFLINE_FIRST` (default): the mesh is the main path; the server helps when the mesh has no route
     right now and for retries after an ACK timeout.
   - `ONLINE_FIRST`: an online sender or border node goes straight through the server.
5. No route at all? Store-and-forward queue (priority, expiry, size cap), retried on a timer.

Physical note: a zone-to-zone link is a logical link between two phones. Two zones can only really link
offline if some of their users are within radio range (Bluetooth about 10-100 m, Wi-Fi Direct a bit more).
Zones far apart still reach each other, but through the zones in between.

Packet header is 22 bytes. AES-GCM nonce is derived from msgId+src, so encryption adds only the 16 byte tag.

## Using it in an app (developer guide)
Simplest way, `MeshEngine` (thread-safe, handles timers and threads for you):

    MeshEngine engine = MeshEngine.builder(myNodeId)
        .zone(myZoneId).zoneCount(40).zones(ZoneRouter.blockZones(16))
        .transport(myBluetoothTransport)                      // you implement 3 methods
        .server(myWebSocketBridge)                            // optional internet helper
        .mode(DeliveryMode.OFFLINE_FIRST)
        .crypto(new AesGcmCrypto(key32))                      // optional
        .storage(new FileStorage(new File(filesDir, "mesh"))) // optional: survive restarts
        .onMessage((from, bytes) -> ...)
        .onDelivery(listener)                                 // onAcked / onFailed
        .build();
    engine.start();                  // restores queued + unconfirmed messages from disk
    long id = engine.send(otherNodeId, bytes);
    engine.stop();

Callbacks run on the engine thread: post to your UI thread before touching views.

**Every part also works alone:**

| class | use it for |
|---|---|
| `Packet` | the 22 byte binary wire format (encode / decode, type, attempt, TTL) |
| `DedupCache` | "have I seen this message id" with expiry and size cap |
| `RouteCache` | LRU next-hop table with expiry |
| `StoreAndForward` | priority queue for messages without a route; optionally disk-backed |
| `StorageAdapter` | implement over SQLite / Room / MMKV; `FileStorage` and `MemoryStorage` included |
| `GatewayManager` | score and elect gateways, re-elect without flapping |
| `ZoneRouter` | zone ring, shortcut links, greedy next hop |
| `AesGcmCrypto`, `KeyExchange` | payload encryption with 16 byte overhead, X25519 |
| `MeshNode` | the whole engine without threading, for your own scheduler or for simulation |
| `Transport`, `ServerBridge`, `Clock` | the three things you plug in |
| `sim` module | test routing changes on 1,000 to 1,000,000 virtual nodes before shipping them |

Persistence: queued packets (`sf-*`) and messages sent but not yet confirmed (`out-*`) are written to the
StorageAdapter. After a crash or restart `start()` brings them back and re-sends the unconfirmed ones.
Not persisted yet: learned routes (soft state, relearned in one flood) and the "already delivered" list.

## Flood optimisation (inside a zone)
Relays wait a random 0..`floodJitterMs` before forwarding. While waiting they note which neighbours already
sent them the packet and skip those (sender suppression, loses nothing). If `counterThreshold` neighbours
already sent it, the relay stays silent (counter suppression). Gateways never suppress.

## Route learning + ACK + fallback
- The neighbour a packet first arrived from is the way back to its sender. Every node on a flood remembers
  that (`RouteCache`, LRU, expiring), so one flood teaches the whole zone how to reach the sender.
  Replies and follow-ups then go as unicast. Packets for another zone are unicast toward a gateway.
- The destination answers with a 22 byte ACK. No ACK after `ackTimeoutMs` (doubling each retry):
  the sender deletes its learned route and re-sends by flood (`attempt` 1, 2, ...). A retransmission
  always floods and has its own dedup key, so a stale route can never block it. The destination
  delivers each message once and re-ACKs repeats.
- A learned next hop that is no longer a live neighbour is skipped immediately (no timeout needed).
- Switches: `routeLearning`, `ackEnabled`, `maxRetries`, `ackTimeoutMs`; simulator `--classic true` = plain flooding.

Measured (10,000 nodes, 3 seeds, 20% online, 1% churn, 5% gateway failure). tx/msg includes ACK traffic:

| traffic | variant | delivery | tx/message | avg latency |
|---|---|---|---|---|
| 6-message conversations | classic flood | 99.93% | 52.4 | 216 ms |
| | suppression only | 99.93% | 47.5 | 250 ms |
| | **routes + ACK (default)** | **99.98%** | **22.1** | 241 ms |
| | routes, no ACK | 99.82% | 14.1 | 238 ms |
| one-shot (every message a new pair) | classic flood | 100.00% | 53.6 | 217 ms |
| | **routes + ACK (default)** | 99.95% | 43.8 | 254 ms |

Without ACK the routes are cheaper but fragile (delivery drops); ACK + fallback costs about 8 tx/message and fixes it.
ACK on top of plain flooding is a bad deal (96.9 tx/message): the ACK itself gets flooded back.
Reproduce: `java -cp ... com.herabarman.meshnet.sim.FloodBench 10000 3`.

## Dashboard
    gradle :dashboard:run                      # then open http://127.0.0.1:8080 in the phone/PC browser
    java -cp core/build/classes/java/main:sim/build/classes/java/main:dashboard/build/classes/java/main:dashboard/build/resources/main \
         com.herabarman.meshnet.dashboard.DashboardMain --port 8080

Shows zones (dashed circles), nodes (green = online, grey = offline, red ring = down), gateways (white ring, ring mode only),
zone-to-zone offline links (amber), and the learned route between the selected nodes A and B (blue).
Tap a node for A, another for B. Buttons: local / remote / A to B message (packets fly as dots, ACKs in blue,
server path in purple), kill A, revive A, kill the gateways of A's zone, pause. Settings: nodes, zone size,
online %, gateways, flood suppression / route learning / ACK on or off (these rebuild the network), and live
sliders for packet loss, churn, background traffic, speed (0.1x to 20x).
It binds to 127.0.0.1 only (`--host 0.0.0.0` to open it to your network, but the page can kill nodes).
Keep nodes under about 600 so the picture stays readable.

Measured (1,000 nodes in 40 zones, same traffic, 20% online, churn and gateway failures on):

| backbone | server | delivery | avg hops | avg latency | tx/message |
|---|---|---|---|---|---|
| ring + shortcuts | first | 99.9% | 4.93 | 213 ms | 34.6 |
| ring + shortcuts | helps only | 99.9% | 5.40 | 229 ms | 28.4 |
| full zone mesh | first | 99.9% | 4.09 | 160 ms | 30.8 |
| **full zone mesh** | **helps only (default)** | 99.9% | 4.27 | 164 ms | 24.2 |

100,000 nodes (ring, 6,666 zones): 100% delivered, 6.3 hops, 330 ms.

## Run
    gradle test
    gradle :sim:run --args="--nodes 1000,10000,100000 --messages 2000 --csv results.csv"

Options: --backbone auto|full|chord --links 2 --mode offline-first --zoneSize --gateways --online --churn --gwFail --messages --local --loss --seed --encrypt --csv

## Porting to the app
Implement `Transport` over Bluetooth/Wi-Fi Direct (peerId = stable int per device), a `Clock` over Handler,
and a `ServerBridge` over your WebSocket. Then `new MeshNode(...)`. The routing code does not change.

## Known simplifications (simulator)
- The gateway directory (who is gateway of zone X) is read from global state. The real app needs gossip/DHT for that.
- Any two gateways can link. In reality backbone links need internet or long-range radio; Bluetooth cannot span zones 3 km apart.
- Phase 2: dashboard (live topology + sliders).
