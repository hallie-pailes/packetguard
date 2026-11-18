import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.packetguard.config.GuardConfig;
import com.packetguard.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 * Runs the RuleEngine against stub Minecraft classes. This checks the engine's own logic
 * (ceilings, shadow evaluation, phases, strikes, hot reload, metrics). It cannot check that the
 * real 26.3 API matches the stubs.
 */
public class EngineSim {
    static int failures = 0, checks = 0;

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + name); }
        else System.out.println("  ok:   " + name);
    }

    static class ChestBE extends BlockEntity implements Container {
        ChestBE(int x, int y, int z) { pos = new BlockPos(x, y, z); }
    }

    static class Fx {
        Path dir; GuardConfig cfg; RuleEngine engine; MinecraftServer server; ServerPlayer player;
        Fx(GuardConfig.Mode mode, Visibility ceiling, boolean shadow) throws Exception {
            dir = Files.createTempDirectory("pg");
            cfg = GuardConfig.loadOrCreate(dir.resolve("config.json"));
            cfg.mode = mode; cfg.visibilityCeiling = ceiling; cfg.shadowEvaluation = shadow;
            cfg.strikeLimit = 3;
            server = new MinecraftServer();
            player = newPlayer(server);
            engine = new RuleEngine(cfg, dir);
        }
        List<Map<String, Object>> events() throws Exception {
            Path p = dir.resolve("events.jsonl");
            List<Map<String, Object>> out = new ArrayList<>();
            if (!Files.exists(p)) return out;
            for (String line : Files.readAllLines(p))
                out.add(new Gson().fromJson(line, new TypeToken<Map<String, Object>>() {}.getType()));
            return out;
        }
    }

    static ServerPlayer newPlayer(MinecraftServer server) {
        ServerPlayer p = new ServerPlayer();
        Level lvl = new Level(); lvl.server = server; p.level = lvl;
        p.connection = new ServerGamePacketListenerImpl(); p.connection.player = p;
        p.inventoryMenu = new AbstractContainerMenu(); p.inventoryMenu.containerId = 0;
        for (int i = 0; i < 46; i++) p.inventoryMenu.slots.add(new Slot(null)); // vanilla inventory menu has 46
        p.containerMenu = p.inventoryMenu;
        return p;
    }

    static AbstractContainerMenu chestMenu(int id, Container backing) {
        AbstractContainerMenu m = new AbstractContainerMenu(); m.containerId = id;
        for (int i = 0; i < 63; i++) m.slots.add(new Slot(i == 0 ? backing : null));
        return m;
    }

    static PacketInfo click(int container, int slot) {
        return new PacketInfo(PacketKind.CONTAINER_CLICK, container, slot, null, null, -1);
    }
    static PacketInfo payload(String ch) {
        return new PacketInfo(PacketKind.CUSTOM_PAYLOAD, -1, PacketInfo.NO_SLOT, null, ch, -1);
    }
    static PacketInfo blockUse(int x, int y, int z) {
        return new PacketInfo(PacketKind.BLOCK_USE, -1, PacketInfo.NO_SLOT, new BlockPos(x, y, z), null, 5);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("T1 context rule is a soft rejection (no strikes)");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          f.player.containerMenu = chestMenu(3, null);
          boolean any = false;
          for (int i = 0; i < 10; i++) any |= f.engine.allow(f.player, click(7, 5), null);
          check("click for the wrong menu is blocked", !any);
          f.server.runQueued();
          check("10 soft rejections never kick (strikeLimit=3)", f.player.connection.disconnects.isEmpty());
          check("event says container_context / GAME_STATE", f.events().get(0).get("rule").equals("container_context")
                  && f.events().get(0).get("minVisibility").equals("GAME_STATE")); }

        System.out.println("T2 ceiling PACKET cannot see context (proxy-equivalent)");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.PACKET, false);
          f.player.containerMenu = chestMenu(3, null);
          check("same click is allowed at ceiling PACKET", f.engine.allow(f.player, click(7, 5), null)); }

        System.out.println("T3 shadow evaluation in MONITOR: what a proxy would have missed");
        { Fx f = new Fx(GuardConfig.Mode.MONITOR, Visibility.CONNECTION, true);
          f.player.containerMenu = chestMenu(3, null);
          check("MONITOR never blocks", f.engine.allow(f.player, click(7, 5), null));
          Map<String, Object> e = f.events().get(0);
          check("logged with blocked=false", Boolean.FALSE.equals(e.get("blocked")));
          check("minVisibility=GAME_STATE (invisible to a proxy)", "GAME_STATE".equals(e.get("minVisibility")));
          check("metrics count it", f.engine.metrics().minVisibilityCount(Visibility.GAME_STATE) == 1); }

        System.out.println("T4 structural rule blocks, hard rejections kick");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.PACKET, false);
          boolean any = false;
          for (int i = 0; i < 3; i++) any |= f.engine.allow(f.player, click(0, 5000), null);
          check("slot 5000 blocked at PACKET level", !any);
          check("rejection visibility PACKET", "PACKET".equals(f.events().get(0).get("minVisibility")));
          check("kick queued on main thread", f.server.queued.size() == 1);
          f.server.runQueued();
          check("player disconnected once", f.player.connection.disconnects.size() == 1); }

        System.out.println("T5 slot bounds depend on the open menu");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          f.player.containerMenu = chestMenu(3, null);
          check("slot 90 (menu has 63) blocked by slot_bounds", !f.engine.allow(f.player, click(3, 90), null)
                  && "slot_bounds".equals(f.events().get(0).get("rule")));
          check("slot 62 allowed", f.engine.allow(f.player, click(3, 62), null));
          check("slot -999 (outside window) allowed", f.engine.allow(f.player, click(3, -999), null));
          Fx g = new Fx(GuardConfig.Mode.ENFORCE, Visibility.CONNECTION, false);
          g.player.containerMenu = chestMenu(3, null);
          check("slot 90 allowed at ceiling CONNECTION (proxy cannot know slot count)",
                  g.engine.allow(g.player, click(3, 90), null)); }

        System.out.println("T6 rate limit is CONNECTION level");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.CONNECTION, false);
          f.cfg.rates.put("CONTAINER_CLICK", new GuardConfig.RateConfig(3, 0));
          int ok = 0; for (int i = 0; i < 6; i++) if (f.engine.allow(f.player, click(0, 1), null)) ok++;
          check("only 3 of 6 pass at CONNECTION", ok == 3);
          Fx g = new Fx(GuardConfig.Mode.ENFORCE, Visibility.PACKET, false);
          g.cfg.rates.put("CONTAINER_CLICK", new GuardConfig.RateConfig(3, 0));
          int ok2 = 0; for (int i = 0; i < 6; i++) if (g.engine.allow(g.player, click(0, 1), null)) ok2++;
          check("all 6 pass at PACKET (rate rule is above the ceiling)", ok2 == 6); }

        System.out.println("T7 creative mode");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          PacketInfo c = new PacketInfo(PacketKind.CREATIVE_SLOT, -1, 10, null, null, -1);
          check("survival creative-set blocked", !f.engine.allow(f.player, c, null));
          f.player.creative = true;
          check("creative allowed", f.engine.allow(f.player, c, null)); }

        System.out.println("T8 reach");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          check("block 100 away blocked", !f.engine.allow(f.player, blockUse(100, 0, 100), null));
          check("block 3 away allowed", f.engine.allow(f.player, blockUse(3, 0, 0), null));
          check("exactly at reach+tolerance edge allowed (4.5+1.5 from eye)",
                  f.engine.allow(f.player, blockUse(5, 0, 0), null)); }

        System.out.println("T9 container range (stillValid per packet + distance)");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          AbstractContainerMenu m = chestMenu(3, new ChestBE(50, 0, 0)); f.player.containerMenu = m;
          check("chest 50 blocks away blocked", !f.engine.allow(f.player, click(3, 1), null)
                  && "container_range".equals(f.events().get(0).get("rule")));
          AbstractContainerMenu near = chestMenu(4, new ChestBE(2, 0, 0)); f.player.containerMenu = near;
          check("chest 2 blocks away allowed", f.engine.allow(f.player, click(4, 1), null));
          near.valid = false;
          check("stillValid=false blocked", !f.engine.allow(f.player, click(4, 1), null));
          AbstractContainerMenu nobe = chestMenu(5, null); f.player.containerMenu = nobe;
          check("no single backing block (double chest): unknown distance is not 'far'",
                  f.engine.allow(f.player, click(5, 1), null)); }

        System.out.println("T10 payload policy and the two-phase path");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          GuardConfig.PayloadPolicy p = new GuardConfig.PayloadPolicy();
          p.requireOpenMenu = true; p.maxPerSecond = 2; p.burst = 2; p.forbidSpectator = true;
          f.cfg.payloadPolicies.put("mod:xfer", p);
          int pass = 0;
          for (int i = 0; i < 3; i++) if (f.engine.allow(f.player, payload("mod:xfer"), null, Phase.THREAD_SAFE)) pass++;
          check("network-thread phase: per-channel burst of 2 enforced", pass == 2);
          check("no-menu payload passes the thread-safe phase (state rules do not run there)", pass == 2);
          check("main-thread phase: no open menu -> blocked softly",
                  !f.engine.allow(f.player, payload("mod:xfer"), null, Phase.STATE_ONLY));
          f.server.runQueued();
          check("soft payload rejection never kicks", f.player.connection.disconnects.isEmpty());
          f.player.containerMenu = chestMenu(3, null);
          check("main-thread phase: menu open -> allowed", f.engine.allow(f.player, payload("mod:xfer"), null, Phase.STATE_ONLY));
          f.player.spectator = true;
          check("spectator blocked", !f.engine.allow(f.player, payload("mod:xfer"), null, Phase.STATE_ONLY));
          check("unlisted channel unaffected", f.engine.allow(f.player, payload("other:chan"), null, Phase.STATE_ONLY)); }

        System.out.println("T11 a payload is counted once across both phases");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          f.engine.allow(f.player, payload("a:b"), null, Phase.THREAD_SAFE);
          f.engine.allow(f.player, payload("a:b"), null, Phase.STATE_ONLY);
          check("evaluated(CUSTOM_PAYLOAD)==1", f.engine.metrics().evaluatedCount(PacketKind.CUSTOM_PAYLOAD) == 1);
          Fx g = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          g.cfg.rates.put("CUSTOM_PAYLOAD", new GuardConfig.RateConfig(1, 0));
          g.engine.allow(g.player, payload("a:b"), null, Phase.THREAD_SAFE);
          boolean second = g.engine.allow(g.player, payload("a:b"), null, Phase.THREAD_SAFE);
          check("2nd payload dropped on the network thread", !second);
          // The first payload passed the network-thread phase and would be counted by the state
          // phase (not run here); only the blocked one is counted at this point.
          check("only the blocked payload is counted by the network-thread phase",
                  g.engine.metrics().evaluatedCount(PacketKind.CUSTOM_PAYLOAD) == 1); }

        System.out.println("T12 disabledRules (ablation of a single rule)");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          f.cfg.disabledRules.add("reach");
          check("reach disabled -> far block allowed", f.engine.allow(f.player, blockUse(100, 0, 100), null)); }

        System.out.println("T13 resync callback runs only when actually blocked");
        { Fx f = new Fx(GuardConfig.Mode.ENFORCE, Visibility.GAME_STATE, false);
          int[] calls = {0};
          f.engine.allow(f.player, click(0, 5000), () -> calls[0]++);
          f.engine.allow(f.player, click(0, 5), () -> calls[0]++);
          check("resync called once (for the blocked packet only)", calls[0] == 1);
          Fx g = new Fx(GuardConfig.Mode.MONITOR, Visibility.GAME_STATE, false);
          int[] c2 = {0};
          g.engine.allow(g.player, click(0, 5000), () -> c2[0]++);
          check("MONITOR never calls resync", c2[0] == 0); }

        System.out.println("T14 hot reload");
        { Fx f = new Fx(GuardConfig.Mode.MONITOR, Visibility.GAME_STATE, false);
          Path cfgFile = f.dir.resolve("config.json");
          check("starts with the in-memory config", f.engine.config().mode == GuardConfig.Mode.MONITOR);
          String json = Files.readString(cfgFile).replace("\"MONITOR\"", "\"ENFORCE\"").replace("\"GAME_STATE\"", "\"PACKET\"");
          Files.writeString(cfgFile, json);
          Files.setLastModifiedTime(cfgFile, FileTime.fromMillis(System.currentTimeMillis() + 5000));
          f.engine.reloadIfChanged();
          check("edited file is picked up", f.engine.config().mode == GuardConfig.Mode.ENFORCE
                  && f.engine.config().visibilityCeiling == Visibility.PACKET);
          Files.writeString(cfgFile, "{ this is not json");
          Files.setLastModifiedTime(cfgFile, FileTime.fromMillis(System.currentTimeMillis() + 10000));
          f.engine.reloadIfChanged();
          check("broken file keeps the previous config", f.engine.config().mode == GuardConfig.Mode.ENFORCE);
          boolean marked = false;
          for (Map<String, Object> e : f.events()) if ("config_reload".equals(e.get("event"))) marked = true;
          check("reload is marked in the event log", marked); }

        System.out.println("T15 config defaults round-trip and metrics flush");
        { Fx f = new Fx(GuardConfig.Mode.MONITOR, Visibility.GAME_STATE, false);
          GuardConfig again = GuardConfig.loadOrCreate(f.dir.resolve("config.json"));
          check("defaults survive a round trip", again.structural.maxSlot == 4096 && again.rates.containsKey("CONTAINER_CLICK")
                  && again.visibilityCeiling == Visibility.GAME_STATE);
          f.engine.allow(f.player, click(0, 5000), null); f.engine.flushMetrics();
          String csv = Files.readString(f.dir.resolve("metrics.csv"));
          check("metrics.csv has the visibility section", csv.contains("min_visibility_of_rejections,PACKET,1"));
          check("metrics.csv has latency percentiles", csv.contains("latency_ns,p50,") && csv.contains("latency_ns,p99,")); }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
