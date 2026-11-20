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
2. 1-2 gateways per zone, elected by battery + signal + internet. Re-elected on failure, healthy ones keep their role.
3. Backbone: zones sit on a ring, each zone links to zones at +-1, +-2, +-4 ... Greedy routing, about log2(zones) hops.
4. Server fast path: the sender or a gateway with internet sends straight through the server if the destination is online.
5. No route? Store-and-forward queue (priority, expiry, size cap), retried on a timer.

Packet header is 22 bytes. AES-GCM nonce is derived from msgId+src, so encryption adds only the 16 byte tag.

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

Shows zones (dashed circles), nodes (green = online, grey = offline, red ring = down), gateways (white ring),
backbone links (amber), and the learned route between the selected nodes A and B (blue).
Tap a node for A, another for B. Buttons: local / remote / A to B message (packets fly as dots, ACKs in blue,
server path in purple), kill A, revive A, kill the gateways of A's zone, pause. Settings: nodes, zone size,
online %, gateways, flood suppression / route learning / ACK on or off (these rebuild the network), and live
sliders for packet loss, churn, background traffic, speed (0.1x to 20x).
It binds to 127.0.0.1 only (`--host 0.0.0.0` to open it to your network, but the page can kill nodes).
Keep nodes under about 600 so the picture stays readable.

## Run
    gradle test
    gradle :sim:run --args="--nodes 1000,10000,100000 --messages 2000 --csv results.csv"

Options: --zoneSize --gateways --online --churn --gwFail --messages --local --loss --seed --encrypt --csv

## Porting to the app
Implement `Transport` over Bluetooth/Wi-Fi Direct (peerId = stable int per device), a `Clock` over Handler,
and a `ServerBridge` over your WebSocket. Then `new MeshNode(...)`. The routing code does not change.

## Known simplifications (simulator)
- The gateway directory (who is gateway of zone X) is read from global state. The real app needs gossip/DHT for that.
- Any two gateways can link. In reality backbone links need internet or long-range radio; Bluetooth cannot span zones 3 km apart.
- Phase 2: dashboard (live topology + sliders).
