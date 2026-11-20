package com.herasoft.meshnet.sim;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: java -jar sim.jar [--nodes 1000,10000,100000] [--zoneSize 15] [--gateways 2]
 *   [--online 0.2] [--churn 0.01] [--gwFail 0.05] [--messages 1000] [--local 0.3]
 *   [--loss 0.0] [--seed 1] [--encrypt true] [--csv results.csv]
 *   [--floodJitter 40] [--counter 3] [--fanout 0] [--classic true]  (flood optimisation; classic = old flooding)
 * A comma list for --nodes runs one simulation per size.
 */
public final class SimMain {
    public static void main(String[] args) throws IOException {
        SimConfig base = new SimConfig();
        List<Integer> sizes = new ArrayList<>();
        String csv = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String k = args[i];
            String v = args[i + 1];
            switch (k) {
                case "--nodes": for (String s : v.split(",")) sizes.add(Integer.parseInt(s.trim())); break;
                case "--zoneSize": base.zoneSize = Integer.parseInt(v); break;
                case "--gateways": base.gatewayCount = Integer.parseInt(v); break;
                case "--online": base.onlineRatio = Double.parseDouble(v); break;
                case "--churn": base.churn = Double.parseDouble(v); break;
                case "--gwFail": base.gatewayFail = Double.parseDouble(v); break;
                case "--messages": base.messages = Integer.parseInt(v); break;
                case "--local": base.localRatio = Double.parseDouble(v); break;
                case "--loss": base.lossRate = Double.parseDouble(v); break;
                case "--seed": base.seed = Long.parseLong(v); break;
                case "--encrypt": base.encrypt = Boolean.parseBoolean(v); break;
                case "--floodJitter": base.floodJitterMs = Integer.parseInt(v); break;
                case "--counter": base.counterThreshold = Integer.parseInt(v); break;
                case "--fanout": base.floodFanout = Integer.parseInt(v); break;
                case "--classic":
                    if (Boolean.parseBoolean(v)) { base.floodJitterMs = 0; base.counterThreshold = 0; base.floodFanout = 0; }
                    break;
                case "--csv": csv = v; break;
                default: System.err.println("unknown option " + k); System.exit(2);
            }
        }
        if (sizes.isEmpty()) sizes.add(base.nodes);

        boolean writeHeader = csv != null && !new File(csv).exists();
        FileWriter out = csv == null ? null : new FileWriter(csv, true);
        if (out != null && writeHeader) out.write(Metrics.csvHeader() + "\n");
        for (int size : sizes) {
            SimConfig cfg = base.copy();
            cfg.nodes = size;
            long t0 = System.currentTimeMillis();
            Metrics m = Simulation.run(cfg);
            System.out.println(m.summary(cfg) + String.format("%n  (wall time %.1fs)%n", (System.currentTimeMillis() - t0) / 1000.0));
            if (out != null) out.write(m.csvRow(cfg) + "\n");
        }
        if (out != null) out.close();
    }
}
