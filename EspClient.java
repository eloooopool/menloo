package com.leek.esp;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

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
