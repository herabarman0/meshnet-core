# MeshNet Core

Pure-Java mesh engine + simulator. Phase 1 = `core` + `sim` + tests. Dashboard is phase 2.

## Modules
- `core`  – the engine (Java 11, no Android imports): Packet, DedupCache, MeshNode, GatewayManager,
            ZoneRouter, StoreAndForward, Crypto (AES-GCM, X25519), Transport / ServerBridge / Clock interfaces
- `sim`   – virtual-clock simulator, 1k to 100k+ nodes, churn, gateway failure, metrics, CSV export
- `android-adapter` – stub showing what BluetoothTransport has to implement

## Topology
1. Zone = P2P cluster (8-20 nodes, ring + random chords). Flooding only inside the zone (TTL + dedup).
2. 1-2 gateways per zone, elected by battery + signal + internet. Re-elected on failure, healthy ones keep their role.
3. Backbone: zones sit on a ring, each zone links to zones at +-1, +-2, +-4 ... Greedy routing, about log2(zones) hops.
4. Server fast path: the sender or a gateway with internet sends straight through the server if the destination is online.
5. No route? Store-and-forward queue (priority, expiry, size cap), retried on a timer.

Packet header is 22 bytes. AES-GCM nonce is derived from msgId+src, so encryption adds only the 16 byte tag.

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
