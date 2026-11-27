package com.leek.esp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 2D player ESP overlay.
 * Coordinate system: Minecraft world space (Y up), camera-relative rendering.
 * Screen space: GUI-scaled pixels, origin top-left.
 */
final class EspClient {
    private static final double MAX_DISTANCE = 128.0;

    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.leekesp.toggle", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT, "key.categories.misc");

    private static boolean enabled = true;

    // Captured each frame in the level render stage, used when the GUI is drawn.
    private static final Matrix4f viewProj = new Matrix4f();
    private static Vec3 camPos = Vec3.ZERO;
    private static float partialTick;
    private static boolean matrixValid;

    private EspClient() {}

    static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(EspClient::onRegisterKeys);
        MinecraftForge.EVENT_BUS.addListener(EspClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EspClient::onLevelStage);
        MinecraftForge.EVENT_BUS.addListener(EspClient::onRenderGui);
        XrayClient.init();
        AutoMiner.init();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        while (TOGGLE.consumeClick()) {
            enabled = !enabled;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("ESP: " + (enabled ? "ON" : "OFF")), true);
            }
        }
    }

    private static void onLevelStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        // proj * view (view = camera rotation only; vertices are camera-relative)
        viewProj.set(e.getProjectionMatrix()).mul(e.getPoseStack().last().pose());
        camPos = e.getCamera().getPosition();
        partialTick = e.getPartialTick();
        matrixValid = true;
    }

    private static void onRenderGui(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !matrixValid || mc.level == null || mc.player == null || mc.options.hideGui) return;

        GuiGraphics g = e.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive()) continue;
            double dist = mc.player.distanceTo(p);
            if (dist > MAX_DISTANCE) continue;

            Vec3 delta = p.getPosition(partialTick).subtract(p.position());
            AABB box = p.getBoundingBox().move(delta);

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean visible = true;
            double[] pt = new double[2];

            for (int i = 0; i < 8 && visible; i++) {
                double x = (i & 1) == 0 ? box.minX : box.maxX;
                double y = (i & 2) == 0 ? box.minY : box.maxY;
                double z = (i & 4) == 0 ? box.minZ : box.maxZ;
                if (!worldToScreen(x, y, z, sw, sh, pt)) { visible = false; break; }
                minX = Math.min(minX, pt[0]); maxX = Math.max(maxX, pt[0]);
                minY = Math.min(minY, pt[1]); maxY = Math.max(maxY, pt[1]);
            }
            // Entities with any corner behind the camera are skipped (no clipping).
            if (!visible) continue;
            if (maxX - minX > sw * 4 || maxY - minY > sh * 4) continue;

            drawPlayer(g, mc, p, dist, (int) minX, (int) minY, (int) maxX, (int) maxY);
        }
    }

    private static void drawPlayer(GuiGraphics g, Minecraft mc, Player p, double dist,
                                   int x1, int y1, int x2, int y2) {
        int color = 0xFFFF3030;
        // box outline
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);

        // health bar on the left side
        float max = Math.max(p.getMaxHealth(), 1.0f);
        float ratio = Math.max(0f, Math.min(1f, p.getHealth() / max));
        int barH = y2 - y1;
        int fill = (int) (barH * ratio);
        g.fill(x1 - 4, y1, x1 - 2, y2, 0xFF202020);
        g.fill(x1 - 4, y2 - fill, x1 - 2, y2, ratio > 0.5f ? 0xFF30FF30 : (ratio > 0.25f ? 0xFFFFD030 : 0xFFFF3030));

        String label = p.getName().getString() + " [" + (int) dist + "m]";
        int tw = mc.font.width(label);
        g.drawString(mc.font, label, (x1 + x2) / 2 - tw / 2, y1 - 10, 0xFFFFFFFF, true);
    }

    /** Returns false when the point is behind the camera. out = {x, y} in GUI pixels. */
    private static boolean worldToScreen(double x, double y, double z, int sw, int sh, double[] out) {
        Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1f);
        viewProj.transform(v);
        if (!(v.w > 0.05f)) return false;
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        if (!Float.isFinite(nx) || !Float.isFinite(ny)) return false;
        out[0] = (nx * 0.5f + 0.5f) * sw;
        out[1] = (1f - (ny * 0.5f + 0.5f)) * sh;
        return true;
    }
}

/** Ore categories. Tags are Forge common tags (forge:ores/<tagPath>). */
enum OreKind {
    COAL("coal", "coal", "уголь", 0, 0xFF404040),
    COPPER("copper", "copper", "медь", 1, 0xFFE07040),
    IRON("iron", "iron", "железо", 1, 0xFFD8B090),
    LAPIS("lapis", "lapis", "лазурит", 1, 0xFF2040E0),
    GOLD("gold", "gold", "золото", 2, 0xFFFFD020),
    REDSTONE("redstone", "redstone", "редстоун", 2, 0xFFFF2020),
    DIAMOND("diamond", "diamond", "алмазы", 2, 0xFF30E0E0),
    EMERALD("emerald", "emerald", "изумруды", 2, 0xFF20E040),
    QUARTZ("quartz", "quartz", "кварц", 0, 0xFFF0F0F0),
    DEBRIS("debris", "netherite_scrap", "древние обломки", 3, 0xFF805030);

    final String id;
    final String ru;
    /** Minimum vanilla pickaxe tier level: 0 wood/gold, 1 stone, 2 iron, 3 diamond. */
    final int minLevel;
    final int color;
    final TagKey<Block> tag;

    OreKind(String id, String tagPath, String ru, int minLevel, int color) {
        this.id = id;
        this.ru = ru;
        this.minLevel = minLevel;
        this.color = color;
        this.tag = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores/" + tagPath));
    }

    static OreKind byId(String s) {
        for (OreKind k : values()) if (k.id.equalsIgnoreCase(s)) return k;
        return null;
    }
}

final class Ores {
    static final TagKey<Block> ANY = TagKey.create(Registries.BLOCK, new ResourceLocation("forge", "ores"));

    record Hit(BlockPos pos, OreKind kind) {}

    private Ores() {}

    static OreKind kindOf(BlockState s) {
        if (s.isAir() || !s.is(ANY)) return null;
        for (OreKind k : OreKind.values()) if (s.is(k.tag)) return k;
        return null;
    }

    static List<String> names() {
        List<String> out = new ArrayList<>();
        for (OreKind k : OreKind.values()) out.add(k.id);
        out.add("random");
        return out;
    }

    /** Scans a box around c. Only reads blocks the client has loaded; unloaded chunks read as air. */
    static List<Hit> scan(Level level, BlockPos c, int rh, int rv, int cap) {
        List<Hit> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinBuildHeight(), c.getY() - rv);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, c.getY() + rv);
        for (int x = c.getX() - rh; x <= c.getX() + rh; x++) {
            for (int z = c.getZ() - rh; z <= c.getZ() + rh; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (s.isAir() || !s.is(ANY)) continue;
                    out.add(new Hit(m.immutable(), kindOf(s)));
                    if (out.size() >= cap) return out;
                }
            }
        }
        return out;
    }

    /** -1 when the stack is not a pickaxe, otherwise the tier level (0 wood/gold, 1 stone, 2 iron, 3 diamond, 4 netherite). */
    static int pickLevel(ItemStack st) {
        return st.getItem() instanceof PickaxeItem pi ? pi.getTier().getLevel() : -1;
    }

    static boolean canHarvest(Player p, BlockState s) {
        return !s.requiresCorrectToolForDrops() || p.getMainHandItem().isCorrectToolForDrops(s);
    }

    static String toolName(int level) {
        return switch (level) {
            case 0 -> "деревянная или золотая";
            case 1 -> "каменная";
            case 2 -> "железная";
            case 3 -> "алмазная";
            default -> "незеритовая";
        };
    }
}

/** Ore X-ray: double-tap sneak to toggle. Draws translucent boxes on ores through walls. */
final class XrayClient {
    private static final int RADIUS_H = 24;
    private static final int RADIUS_V = 16;
    private static final int CAP = 3000;
    private static final int DOUBLE_TAP_TICKS = 26;

    private static boolean enabled;
    private static boolean sneakPrev;
    private static int tick;
    private static int lastPressTick = -1000;
    private static List<Ores.Hit> hits = List.of();

    private XrayClient() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(XrayClient::onTick);
        MinecraftForge.EVENT_BUS.addListener(XrayClient::onRender);
    }

    private static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (mc.player == null || mc.level == null) {
            hits = List.of();
            sneakPrev = false;
            return;
        }

        boolean sneak = mc.options.keyShift.isDown();
        if (sneak && !sneakPrev) {
            if (tick - lastPressTick <= DOUBLE_TAP_TICKS) {
                enabled = !enabled;
                lastPressTick = -1000;
                if (!enabled) hits = List.of();
                mc.player.displayClientMessage(Component.literal("X-Ray: " + (enabled ? "ON" : "OFF")), true);
                tick = 0; // force an immediate rescan on enable
            } else {
                lastPressTick = tick;
            }
        }
        sneakPrev = sneak;

        if (enabled && tick % 20 == 0) {
            hits = Ores.scan(mc.level, mc.player.blockPosition(), RADIUS_H, RADIUS_V, CAP);
        }
    }

    private static void onRender(RenderLevelStageEvent e) {
        if (!enabled || hits.isEmpty()) return;
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Vec3 cam = e.getCamera().getPosition();
        Matrix4f m = e.getPoseStack().last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Ores.Hit h : hits) {
            int c = h.kind() != null ? h.kind().color : 0xFFFFFFFF;
            box(bb, m, h.pos(), cam, c);
        }
        tess.end();

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static void box(VertexConsumer b, Matrix4f m, BlockPos p, Vec3 cam, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float bl = (argb & 0xFF) / 255f;
        float a = 0.45f;
        float x0 = (float) (p.getX() - cam.x) - 0.002f;
        float y0 = (float) (p.getY() - cam.y) - 0.002f;
        float z0 = (float) (p.getZ() - cam.z) - 0.002f;
        float x1 = x0 + 1.004f, y1 = y0 + 1.004f, z1 = z0 + 1.004f;

        quad(b, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a); // bottom
        quad(b, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, bl, a); // top
        quad(b, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl, a); // north
        quad(b, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl, a); // south
        quad(b, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl, a); // west
        quad(b, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl, a); // east
    }

    private static void quad(VertexConsumer b, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float bl, float a) {
        b.vertex(m, ax, ay, az).color(r, g, bl, a).endVertex();
        b.vertex(m, bx, by, bz).color(r, g, bl, a).endVertex();
        b.vertex(m, cx, cy, cz).color(r, g, bl, a).endVertex();
        b.vertex(m, dx, dy, dz).color(r, g, bl, a).endVertex();
    }
}

/**
 * Ore auto-miner. Commands: /automine [ore|random] [count], /automine stop.
 * Walks by tunnelling (no block placing, no real pathfinding): digs the cells it needs
 * one step at a time, towards the target ore, then mines it and walks to pick the drops up.
 */
final class AutoMiner {
    private static final int DEFAULT_COUNT = 5;
    private static final int SEARCH_H = 32;
    private static final int SEARCH_V = 24;
    private static final double MINE_DISTANCE = 3.2;
    private static final float MIN_HEALTH = 6.0f;

    private static boolean active;
    private static boolean keysHeld;
    private static OreKind wanted;      // null = random ore
    private static int goal;
    private static int done;
    private static BlockPos target;
    private static OreKind targetKind;
    private static boolean collecting;
    private static int collectTicks;
    private static Direction ascendDir;
    private static Vec3 lastPos;
    private static int stuck;

    private AutoMiner() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(AutoMiner::onCommands);
        MinecraftForge.EVENT_BUS.addListener(AutoMiner::onTick);
    }

    // ---------------------------------------------------------------- commands

    private static void onCommands(RegisterClientCommandsEvent e) {
        SuggestionProvider<CommandSourceStack> oreSuggest =
                (ctx, builder) -> SharedSuggestionProvider.suggest(Ores.names(), builder);

        e.getDispatcher().register(Commands.literal("automine")
                .executes(c -> start(null, DEFAULT_COUNT))
                .then(Commands.literal("stop").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (active) say(mc, "Автокопание остановлено.", ChatFormatting.YELLOW);
                    stop(mc);
                    return 1;
                }))
                .then(Commands.argument("ore", StringArgumentType.word()).suggests(oreSuggest)
                        .executes(c -> start(StringArgumentType.getString(c, "ore"), DEFAULT_COUNT))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                .executes(c -> start(StringArgumentType.getString(c, "ore"),
                                        IntegerArgumentType.getInteger(c, "count"))))));
    }

    private static int start(String oreArg, int count) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return 0;
        stop(mc);

        OreKind kind = null;
        if (oreArg != null && !oreArg.equalsIgnoreCase("random")) {
            kind = OreKind.byId(oreArg);
            if (kind == null) {
                say(mc, "Неизвестная руда: " + oreArg + ". Доступно: " + String.join(", ", Ores.names()),
                        ChatFormatting.RED);
                return 0;
            }
        }

        ItemStack tool = p.getMainHandItem();
        int level = Ores.pickLevel(tool);
        if (level < 0) {
            say(mc, "Возьми кирку в руку.", ChatFormatting.RED);
            return 0;
        }
        if (kind != null && level < kind.minLevel) {
            say(mc, "Предупреждение: " + kind.ru + " нельзя добыть этой киркой ("
                    + tool.getHoverName().getString() + "). Нужна минимум: "
                    + Ores.toolName(kind.minLevel) + ".", ChatFormatting.RED);
            return 0;
        }

        wanted = kind;
        goal = count;
        done = 0;
        active = true;
        say(mc, "Автокопание: " + (kind == null ? "случайная руда" : kind.ru) + ", нужно " + count
                + ". Остановить: /automine stop", ChatFormatting.GREEN);
        return 1;
    }

    // ---------------------------------------------------------------- tick

    private static void onTick(TickEvent.ClientTickEvent e) {
        // START: runs before the game reads key states for this tick.
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (!active) {
            releaseKeys(mc);
            return;
        }
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            stop(mc);
            return;
        }
        if (mc.screen != null) {
            releaseKeys(mc);
            return;
        }

        if (p.getHealth() <= MIN_HEALTH) { fail("Мало здоровья — остановился."); return; }
        if (Ores.pickLevel(p.getMainHandItem()) < 0) { fail("Кирки нет в руке — остановился."); return; }
        if (lavaNear(level, p.blockPosition())) { fail("Рядом лава — остановился."); return; }

        if (target == null) {
            if (done >= goal) { finish(mc); return; }
            if (!pickTarget(p, level)) {
                fail("Подходящая руда в радиусе " + SEARCH_H + " блоков не найдена"
                        + " (или кирка её не берёт).");
                return;
            }
            collecting = false;
            stuck = 0;
            lastPos = null;
            say(mc, "Цель: " + targetKind.ru + " (" + target.getX() + " " + target.getY() + " "
                    + target.getZ() + "), добыто " + done + "/" + goal, ChatFormatting.AQUA);
        }

        Vec3 tc = Vec3.atCenterOf(target);

        if (collecting) {
            collectTicks++;
            double hd = Math.hypot(p.getX() - tc.x, p.getZ() - tc.z);
            double vd = Math.abs(p.getY() - target.getY());
            if ((hd < 1.2 && vd < 2.0) || collectTicks > 100) {
                target = null;
                collecting = false;
                keys(mc, false, false, false);
                return;
            }
            navigate(mc, p, level, target);
            return;
        }

        BlockState ts = level.getBlockState(target);
        if (!ts.is(Ores.ANY)) {
            done++;
            collecting = true;
            collectTicks = 0;
            keys(mc, false, false, false);
            return;
        }
        if (!Ores.canHarvest(p, ts)) {
            fail("Этой киркой нельзя добыть " + targetKind.ru + ".");
            return;
        }

        if (p.getEyePosition().distanceTo(tc) <= MINE_DISTANCE) {
            stuck = 0;
            aimAndMine(mc, p, level, tc, null);
        } else {
            navigate(mc, p, level, target);
        }
    }

    // ---------------------------------------------------------------- target selection

    private static boolean pickTarget(LocalPlayer p, ClientLevel level) {
        int pickLvl = Ores.pickLevel(p.getMainHandItem());
        List<Ores.Hit> all = Ores.scan(level, p.blockPosition(), SEARCH_H, SEARCH_V, 4000);

        List<Ores.Hit> ok = new ArrayList<>();
        Set<OreKind> kinds = new LinkedHashSet<>();
        for (Ores.Hit h : all) {
            if (h.kind() == null) continue;
            if (wanted != null && h.kind() != wanted) continue;
            if (h.kind().minLevel > pickLvl) continue;
            if (!Ores.canHarvest(p, level.getBlockState(h.pos()))) continue;
            ok.add(h);
            kinds.add(h.kind());
        }
        if (ok.isEmpty()) return false;

        OreKind chosen = wanted;
        if (chosen == null) {
            List<OreKind> list = new ArrayList<>(kinds);
            chosen = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        }

        Ores.Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (Ores.Hit h : ok) {
            if (h.kind() != chosen) continue;
            double d = h.pos().distSqr(p.blockPosition());
            if (d < bestD) { bestD = d; best = h; }
        }
        if (best == null) return false;
        target = best.pos();
        targetKind = chosen;
        return true;
    }

    // ---------------------------------------------------------------- navigation (tunnelling)

    private static void navigate(Minecraft mc, LocalPlayer p, ClientLevel level, BlockPos goalPos) {
        BlockPos pp = p.blockPosition();
        int dx = goalPos.getX() - pp.getX();
        int dy = goalPos.getY() - pp.getY();
        int dz = goalPos.getZ() - pp.getZ();

        Direction h = null;
        if (dx != 0 || dz != 0) {
            h = Math.abs(dx) >= Math.abs(dz)
                    ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (dy <= 0) ascendDir = null;

        List<BlockPos> clear = new ArrayList<>();
        Direction walkDir = h;
        boolean jump = false;
        boolean walk = true;

        if (dy > 0) {
            // Stair up: needs a solid floor in the next column; without block placing we can only climb stairs.
            Direction d = h != null ? h : (ascendDir != null ? ascendDir : Direction.EAST);
            if (h == null) ascendDir = d;
            walkDir = d;
            if (!passable(level, pp.relative(d))) {
                clear.add(pp.above(2));
                clear.add(pp.relative(d).above(2));
                clear.add(pp.relative(d).above());
                jump = true;
            } else {
                clear.add(pp.relative(d).above());
                clear.add(pp.relative(d));
            }
        } else if (dy < 0) {
            if (h != null) {
                // Stair down: open head, feet and the cell below in the next column.
                clear.add(pp.relative(h).above());
                clear.add(pp.relative(h));
                clear.add(pp.relative(h).below());
            } else {
                clear.add(pp.below());
                walk = false;
            }
        } else {
            if (h == null) {
                keys(mc, false, false, false);
                return;
            }
            clear.add(pp.relative(h).above());
            clear.add(pp.relative(h));
        }

        List<BlockPos> need = new ArrayList<>();
        for (BlockPos c : clear) if (!passable(level, c)) need.add(c);
        if (!need.isEmpty()) {
            stuck = 0;
            aimAndMine(mc, p, level, Vec3.atCenterOf(need.get(0)), new HashSet<>(need));
            return;
        }
        if (!walk || walkDir == null) {
            keys(mc, false, false, false);
            return;
        }

        BlockPos col = pp.relative(walkDir);
        int drop = 0;
        BlockPos q = col.below();
        while (drop < 6 && passable(level, q)) {
            drop++;
            q = q.below();
        }
        if (drop > 3) { fail("Обрыв впереди — остановился."); return; }

        faceHorizontal(p, Vec3.atCenterOf(col));
        keys(mc, true, jump, false);
        trackStuck(p);
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean lavaNear(Level level, BlockPos c) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -2; x <= 2; x++)
            for (int y = -2; y <= 2; y++)
                for (int z = -2; z <= 2; z++) {
                    m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                    if (level.getFluidState(m).is(FluidTags.LAVA)) return true;
                }
        return false;
    }

    private static boolean isSafe(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getDestroySpeed(level, pos) < 0) return false;          // bedrock, barriers, ...
        if (level.getBlockEntity(pos) != null) return false;          // chests, furnaces, spawners, ...
        if (level.getFluidState(pos).is(FluidTags.LAVA)) return false;
        for (Direction d : Direction.values()) {
            if (level.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- input

    /**
     * Aims at the point and holds attack only when the vanilla crosshair block is one we are
     * allowed to break (allowed == null means: any safe block, used for the final ore approach).
     */
    private static void aimAndMine(Minecraft mc, LocalPlayer p, ClientLevel level, Vec3 point, Set<BlockPos> allowed) {
        aim(p, point);
        boolean attack = false;
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            if (allowed == null || allowed.contains(bp)) {
                if (!isSafe(level, bp)) {
                    fail("Опасный блок на пути ("
                            + level.getBlockState(bp).getBlock().getName().getString() + ") — остановился.");
                    return;
                }
                attack = true;
            }
        }
        keys(mc, false, false, attack);
    }

    private static void aim(LocalPlayer p, Vec3 point) {
        Vec3 eye = p.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double hz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hz));
        setRotation(p, yaw, pitch);
    }

    private static void faceHorizontal(LocalPlayer p, Vec3 point) {
        double dx = point.x - p.getX();
        double dz = point.z - p.getZ();
        setRotation(p, (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), 0f);
    }

    private static void setRotation(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    private static void keys(Minecraft mc, boolean forward, boolean jump, boolean attack) {
        mc.options.keyUp.setDown(forward);
        mc.options.keyJump.setDown(jump);
        mc.options.keyAttack.setDown(attack);
        keysHeld = true;
    }

    private static void releaseKeys(Minecraft mc) {
        if (!keysHeld) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
        keysHeld = false;
    }

    private static void trackStuck(LocalPlayer p) {
        Vec3 pos = p.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.0004) stuck++; else stuck = 0;
        lastPos = pos;
        if (stuck > 60) fail("Застрял — остановился.");
    }

    // ---------------------------------------------------------------- lifecycle / messages

    private static void stop(Minecraft mc) {
        active = false;
        target = null;
        targetKind = null;
        collecting = false;
        ascendDir = null;
        lastPos = null;
        stuck = 0;
        releaseKeys(mc);
    }

    private static void fail(String msg) {
        Minecraft mc = Minecraft.getInstance();
        say(mc, msg, ChatFormatting.RED);
        stop(mc);
    }

    private static void finish(Minecraft mc) {
        say(mc, "Готово: добыто " + done + " из " + goal + ".", ChatFormatting.GREEN);
        stop(mc);
    }

    private static void say(Minecraft mc, String msg, ChatFormatting color) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg).withStyle(color), false);
        }
    }
}
