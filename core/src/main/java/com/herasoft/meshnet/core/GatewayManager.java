package com.herasoft.meshnet.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picks gateway nodes for a zone. score = battery + signal + internet availability. */
public final class GatewayManager {
    private final double wBattery;
    private final double wSignal;
    private final double wInternet;

    public GatewayManager() {
        this(0.4, 0.3, 0.3);
    }

    public GatewayManager(double wBattery, double wSignal, double wInternet) {
        this.wBattery = wBattery;
        this.wSignal = wSignal;
        this.wInternet = wInternet;
    }

    public double score(NodeInfo n) {
        return wBattery * n.battery + wSignal * n.signal + wInternet * (n.online ? 1.0 : 0.0);
    }

    private List<NodeInfo> aliveSorted(Collection<NodeInfo> members) {
        List<NodeInfo> alive = new ArrayList<>();
        for (NodeInfo n : members) if (n.alive) alive.add(n);
        alive.sort(new Comparator<NodeInfo>() {
            @Override
            public int compare(NodeInfo a, NodeInfo b) {
                int c = Double.compare(score(b), score(a));
                return c != 0 ? c : Integer.compare(a.id, b.id);
            }
        });
        return alive;
    }

    /** Fresh election: the best `count` alive members. */
    public List<Integer> elect(Collection<NodeInfo> members, int count) {
        List<Integer> out = new ArrayList<>();
        for (NodeInfo n : aliveSorted(members)) {
            if (out.size() >= count) break;
            out.add(n.id);
        }
        return out;
    }

    /**
     * Re-election after a failure: healthy current gateways keep their role (no flapping),
     * vacancies are filled with the best remaining members.
     */
    public List<Integer> reelect(Collection<NodeInfo> members, List<Integer> current, int count) {
        Map<Integer, NodeInfo> alive = new HashMap<>();
        for (NodeInfo n : members) if (n.alive) alive.put(n.id, n);
        List<Integer> out = new ArrayList<>();
        for (int id : current) {
            if (out.size() >= count) break;
            if (alive.containsKey(id)) out.add(id);
        }
        if (out.size() < count) {
            for (NodeInfo n : aliveSorted(members)) {
                if (out.size() >= count) break;
                if (!out.contains(n.id)) out.add(n.id);
            }
        }
        return out;
    }
}
