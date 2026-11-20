package com.herabarman.meshnet.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardSimTest {
    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void stateHasEveryFieldThePageReads() {
        DashboardSim sim = new DashboardSim();
        String s = sim.state(0, -1, -1);
        for (String key : new String[]{"\"t\":", "\"n\":120", "\"zoneSize\":12", "\"zoneCount\":10", "\"st\":\"",
                "\"intra\":[", "\"bb\":[", "\"m\":{", "\"events\":[", "\"log\":[", "\"route\":[", "\"settings\":{"}) {
            assertTrue(s.contains(key), "missing " + key);
        }
        int start = s.indexOf("\"st\":\"") + 6;
        assertEquals(120, s.indexOf('"', start) - start);   // one flag character per node
    }

    @Test
    void sentMessageIsTracedDeliveredAndAcked() {
        DashboardSim sim = new DashboardSim();
        String reply = sim.send("remote", -1, -1);
        assertTrue(reply.contains("\"ok\":true"), reply);
        sim.tick(8_000);
        String s = sim.state(0, -1, -1);
        assertTrue(s.contains("পৌঁছেছে"), "no delivery line");
        assertTrue(s.contains("ACK ফিরেছে"), "no ack line");
        assertFalse(s.contains("\"events\":[]"), "tracked message should produce animation events");
        assertTrue(s.contains("\"delivered\":1"));
    }

    @Test
    void untrackedTrafficProducesNoEventsUnlessShowAllIsOn() {
        DashboardSim sim = new DashboardSim();
        sim.configure(q("traffic", "120"));
        sim.tick(20_000);
        assertTrue(sim.state(0, -1, -1).contains("\"events\":[]"));
        sim.configure(q("showAll", "1"));
        sim.tick(20_000);
        assertFalse(sim.state(0, -1, -1).contains("\"events\":[]"));
    }

    @Test
    void structuralChangeRebuildsAndLiveChangeDoesNot() {
        DashboardSim sim = new DashboardSim();
        sim.tick(3_000);
        sim.configure(q("loss", "10"));                       // live: clock keeps running
        assertFalse(sim.state(0, -1, -1).contains("\"t\":0,"));
        sim.configure(q("nodes", "60", "zoneSize", "10"));    // structural: new network
        String s = sim.state(0, -1, -1);
        assertTrue(s.contains("\"n\":60"));
        assertTrue(s.contains("\"zoneCount\":6"));
        assertTrue(s.contains("\"t\":0,"));
    }

    @Test
    void killingZoneGatewaysTriggersReelection() {
        DashboardSim sim = new DashboardSim();
        List<Integer> before = sim.gatewaysOfZone(2);
        sim.killGateways(2);
        sim.tick(5_000);
        List<Integer> after = sim.gatewaysOfZone(2);
        assertFalse(after.isEmpty());
        for (int g : after) assertFalse(before.contains(g), "dead gateway still elected");
    }

    @Test
    void invalidRequestsAreRejectedNotCrashed() {
        DashboardSim sim = new DashboardSim();
        assertTrue(sim.send("local", 5, 5).contains("\"ok\":false"));
        assertTrue(sim.send("local", 9999, 3).contains("\"ok\":false"));
        sim.kill(-5);
        sim.revive(99999);
        sim.killGateways(77);
        assertTrue(sim.state(0, 9999, 9999).contains("\"route\":[]"));
    }
}
