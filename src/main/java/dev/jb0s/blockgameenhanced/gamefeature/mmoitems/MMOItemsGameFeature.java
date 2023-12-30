package dev.jb0s.blockgameenhanced.gamefeature.mmoitems;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.jb0s.blockgameenhanced.BlockgameEnhanced;
import dev.jb0s.blockgameenhanced.BlockgameEnhancedClient;
import dev.jb0s.blockgameenhanced.event.chat.ReceiveChatMessageEvent;
import dev.jb0s.blockgameenhanced.event.gamefeature.mmoitems.ItemUsageEvent;
import dev.jb0s.blockgameenhanced.event.renderer.item.ItemRendererDrawEvent;
import dev.jb0s.blockgameenhanced.gamefeature.GameFeature;
import dev.jb0s.blockgameenhanced.helper.DebugHelper;
import dev.jb0s.blockgameenhanced.helper.MMOItemHelper;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Map;

public class MMOItemsGameFeature extends GameFeature {
    @Getter
    @Setter
    private MMOItemsCooldownEntry globalCooldown;

    private final Gson gson = new Gson();
    private final Map<String, MMOItemsCooldownEntry> cooldownEntryMap = Maps.newHashMap();
    private final ArrayList<ScheduledItemUsePacket> scheduledPackets = new ArrayList<>();
    private final ArrayList<ItemUsageEvent> capturedItemUsages = new ArrayList<>();
    private int tick;

    @Override
    public void init(MinecraftClient minecraftClient, BlockgameEnhancedClient blockgameClient) {
        super.init(minecraftClient, blockgameClient);

        UseBlockCallback.EVENT.register(this::preventIllegalMMOItemsInteraction);
        UseItemCallback.EVENT.register(this::repeatItemUseForCooldownMessage);
        ReceiveChatMessageEvent.EVENT.register(this::visualizeCooldown);
        ItemRendererDrawEvent.EVENT.register(this::drawItemCooldownOverlay);
        ItemRendererDrawEvent.EVENT.register(this::drawItemChargeCounter);
        ClientPlayConnectionEvents.JOIN.register((x, y, z) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((x, y) -> reset());
        globalCooldown = new MMOItemsCooldownEntry(0, 0);
    }

    /**
     * Draws charge count for items with charges
     */
    private ActionResult drawItemChargeCounter(DrawContext context, TextRenderer textRenderer, ItemStack itemStack, int x, int y, String countLabel) {
        NbtCompound nbt = itemStack.getOrCreateNbt();

        if(BlockgameEnhanced.isNotkerMmoPresent()) {
            // Compatibility with Notker's McMMO Item Durability viewer.
            return ActionResult.PASS;
        }

        if(nbt.getInt("MMOITEMS_MAX_CONSUME") != 0 && itemStack.getCount() == 1) {
            String chargeCountString = countLabel == null ? String.valueOf(nbt.getInt("MMOITEMS_MAX_CONSUME")) : countLabel;
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(Tessellator.getInstance().getBuffer());

            // We don't need to revert this because we push to the stack before the event is fired and pop it after the event is finished
            context.getMatrices().translate(0.0f, 0.0f, 210.0f);

            // Shitty outline (Notch did it first!)
            context.drawText(textRenderer, chargeCountString, (x + 19 - 2 - textRenderer.getWidth(chargeCountString)) + 1, (y + 6 + 3), 0x000000, false);
            context.drawText(textRenderer, chargeCountString, (x + 19 - 2 - textRenderer.getWidth(chargeCountString)) - 1, (y + 6 + 3), 0x000000, false);
            context.drawText(textRenderer, chargeCountString, (x + 19 - 2 - textRenderer.getWidth(chargeCountString)), (y + 6 + 3) + 1, 0x000000, false);
            context.drawText(textRenderer, chargeCountString, (x + 19 - 2 - textRenderer.getWidth(chargeCountString)), (y + 6 + 3) - 1, 0x000000, false);

            // Draw actual text
            context.drawText(textRenderer, chargeCountString, (x + 19 - 2 - textRenderer.getWidth(chargeCountString)), (y + 6 + 3), 0x7EFC20, false);

            immediate.draw();
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    /**
     * Draw cooldown for items
     */
    private ActionResult drawItemCooldownOverlay(DrawContext context, TextRenderer textRenderer, ItemStack itemStack, int x, int y, String countLabel) {
        NbtCompound nbt = itemStack.getOrCreateNbt();
        String tag = nbt.getString("MMOITEMS_ABILITY");
        if(tag != null) {
            MMOItemsAbility[] itemAbilities = gson.fromJson(tag, MMOItemsAbility[].class);
            if(itemAbilities != null && itemAbilities.length > 0) {
                float cooldownProgressForThisStack = getCooldownProgress(itemAbilities[0].Id, MinecraftClient.getInstance().getTickDelta());
                float globalCooldownProgress = getCooldownProgress(getGlobalCooldown(), MinecraftClient.getInstance().getTickDelta());
                float cd = cooldownProgressForThisStack == 0.0f ? globalCooldownProgress : cooldownProgressForThisStack;

                // We don't need to revert this because we push to the stack before the event is fired and pop it after the event is finished
                context.getMatrices().translate(0.0f, 0.0f, 200.0f);

                if (cd > 0.0f) {
                    context.fill(x, y + MathHelper.floor(16.0f * (1.0f - cd)), x + 16, y + 16, Integer.MAX_VALUE);
                }
            }
        }

        return ActionResult.PASS;
    }

    @Override
    public synchronized void tick() {
        ++tick;

        if(getMinecraftClient().player == null) {
            return;
        }

        // Remove expired cooldowns
        cooldownEntryMap.entrySet().removeIf(x -> tick > x.getValue().endTick);

        // Send any scheduled packets
        if(!scheduledPackets.isEmpty()) {
            if(getMinecraftClient().getNetworkHandler() == null) {
                scheduledPackets.clear();
                return;
            }

            ScheduledItemUsePacket[] list = getCooldownPacketsSafe();
            for(ScheduledItemUsePacket packet : list) {
                // If it is not time to send this packet yet, skip it
                if (packet.endTick > tick) {
                    continue;
                }

                // Send the packet to trigger a cooldown message
                getMinecraftClient().getNetworkHandler().sendPacket(packet.packet);
            }

            // Remove expired packets (the ones we've just sent)
            scheduledPackets.removeIf(x -> tick > x.endTick);
        }
    }

    /**
     * Callback that checks if the player is trying to place an MMOItem that has interaction disabled, and blocks doing so.
     * @param playerEntity The Player Entity that is trying to place the block.
     * @param world The world in which the Player Entity is trying to place the block.
     * @param hand The hand that contains the block the Player Entity is trying to place.
     * @param blockHitResult The hit result where the block should be placed.
     * @return ActionResult.PASS if the placement is allowed, ActionResult.FAIL if not.
     */
    public ActionResult preventIllegalMMOItemsInteraction(PlayerEntity playerEntity, World world, Hand hand, BlockHitResult blockHitResult) {
        ItemStack handItem = playerEntity.getStackInHand(hand);
        NbtCompound nbt = handItem.getOrCreateNbt();

        // If we have a tag named MMOITEMS_DISABLE_INTERACTION set to true, we need to block placement.
        // If there's a block entity where we clicked, and the player is not sneaking, then the player
        // is trying to interact with a block entity. In that case, we need to let it pass through.
        if(nbt.getBoolean("MMOITEMS_DISABLE_INTERACTION")) {
            BlockEntity b = world.getBlockEntity(blockHitResult.getBlockPos());
            boolean isTryingToInteractWithBlockEntity = b != null && !playerEntity.isSneaking();

            return isTryingToInteractWithBlockEntity ? ActionResult.PASS : ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    /**
     * Callback that repeats a right click action so that the server sends the client a cooldown value to use in the hotbar.
     * @param playerEntity The Player Entity that is trying to use an item.
     * @param world The world in which the Player Entity is trying to use an item.
     * @param hand The hand that contains the item the Player Entity is trying to use.
     * @return Always returns PASS, whether the routine was successful or not.
     */
    public synchronized TypedActionResult<ItemStack> repeatItemUseForCooldownMessage(PlayerEntity playerEntity, World world, Hand hand) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        ItemStack stack = playerEntity.getStackInHand(hand);

        boolean moduleEnabled = BlockgameEnhanced.getConfig().getIngameHudConfig().showCooldownsInHotbar;
        if(!moduleEnabled) return TypedActionResult.pass(stack);

        if(interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return TypedActionResult.pass(stack);
        }

        // If item in hand doesn't have an MMOItems ability, skip
        if(!MMOItemHelper.hasMMOAbility(stack)) {
            return TypedActionResult.pass(stack);
        }

        // This item has an ability, resend the right click packet to trigger a cooldown message from the server, which we then use for the hotbar
        scheduledPackets.add(new ScheduledItemUsePacket(new PlayerInteractItemC2SPacket(hand, tick), tick, tick + 2));
        captureItemUsage(stack);

        return TypedActionResult.pass(stack);
    }

    /**
     * Callback that scans chat messages for cooldown values to display in the hotbar.
     * @param client The MinecraftClient instance.
     * @param message The received message in String format.
     * @return Always returns PASS, whether the routine was successful or not.
     */
    public synchronized ActionResult visualizeCooldown(MinecraftClient client, String message) {
        boolean moduleEnabled = BlockgameEnhanced.getConfig().getIngameHudConfig().showCooldownsInHotbar;
        if(!moduleEnabled) return ActionResult.PASS;

        if(!message.startsWith("[CD]")) {
            return ActionResult.PASS;
        }

        ItemUsageEvent itemUsage = getItemUsage();
        if(itemUsage == null) {
            return ActionResult.PASS;
        }

        ItemStack stack = itemUsage.getItemStack();

        // If item in hand doesn't have an MMOItems ability, skip
        if(!MMOItemHelper.hasMMOAbility(stack)) {
            return ActionResult.PASS;
        }

        // Extract cooldown length from chat message
        String[] spl = message.split(" ");
        String sec = spl[1].replace("s", "").trim();
        float fSec = Float.parseFloat(sec);
        int ticks = (int)(fSec * 20);

        // Add latency to cooldown length
        ticks += (BlockgameEnhancedClient.getLatency() / 1000) * 20;

        // Get MMOAbility and set a cooldown for it
        String abil = MMOItemHelper.getMMOAbility(stack);
        if(abil != null) {
            setCooldown(abil, ticks);

            // how did I forget to not make it spam GCD
            /*float threshold = ((float) BlockgameEnhancedClient.getLatency() / 1000);
            if(getCooldownProgress(getGlobalCooldown(), 0.0f) < threshold) {
                setGlobalCooldown(new MMOItemsCooldownEntry(tick, tick + 20)); // gcd 1.0s
            }*/
        }

        return ActionResult.SUCCESS;
    }

    /**
     * Is "ability" currently cooling down?
     * @param ability Ability ID, e.g. "FROSTBOLT"
     * @return Whether this ability is currently on cooldown.
     */
    public boolean isCoolingDown(String ability) {
        return getCooldownProgress(ability, 0.0f) > 0;
    }

    /**
     * Range 0-1 determining the percentage of completion of a cooldown.
     * @param ability Ability ID, e.g. "FROSTBOLT"
     * @param partialTicks Value to make up for a small imprecision mid-tick, in most cases this will be deltaSeconds.
     * @return Float ranging 0-1.
     */
    public float getCooldownProgress(String ability, float partialTicks) {
        MMOItemsCooldownEntry entry = cooldownEntryMap.get(ability);
        if(entry != null) {
            return getCooldownProgress(entry, partialTicks);
        }

        return 0.0f;
    }

    /**
     * Range 0-1 determining the percentage of completion of a cooldown.
     * @param entry Ability cooldown entry.
     * @param partialTicks Value to make up for a small imprecision mid-tick, in most cases this will be deltaSeconds.
     * @return Float ranging 0-1.
     */
    public float getCooldownProgress(MMOItemsCooldownEntry entry, float partialTicks) {
        if(entry != null) {
            float f = entry.endTick - entry.startTick;
            float g = (float) entry.endTick - ((float)tick + partialTicks);
            return MathHelper.clamp(g / f, 0.0f, 1.0f);
        }

        return 0.0f;
    }

    /**
     * Set an ability's cooldown length in ticks.
     * @param ability Ability ID, e.g. "FROSTBOLT"
     * @param durationTicks
     */
    public void setCooldown(String ability, int durationTicks) {
        if(!cooldownEntryMap.containsKey(ability)) {
            cooldownEntryMap.put(ability, new MMOItemsCooldownEntry(tick, tick + durationTicks));
        }
    }

    /**
     * Clear an ability's cooldown.
     * @param ability Ability ID, e.g. "FROSTBOLT"
     */
    public void removeCooldown(String ability) {
        cooldownEntryMap.remove(ability);
    }

    public void captureItemUsage(ItemStack itemStack) {
        ItemUsageEvent event = new ItemUsageEvent(itemStack);
        capturedItemUsages.add(event);
    }

    /**
     * Resets all the values.
     */
    private synchronized void reset() {
        tick = 0;
        cooldownEntryMap.clear();
        scheduledPackets.clear();
        capturedItemUsages.clear();
    }

    public synchronized ItemUsageEvent getItemUsage() {
        return getItemUsage(BlockgameEnhancedClient.getLatency());
    }

    public synchronized ItemUsageEvent getItemUsage(int latency) {
        long targetLatency = System.currentTimeMillis() - latency;

        ItemUsageEvent winning = null;
        long winningLatency = Long.MAX_VALUE;

        for (ItemUsageEvent e : capturedItemUsages) {
            long dif = Math.abs(e.getTimeMs() - targetLatency);

            if(dif < winningLatency) {
                winningLatency = dif;
                winning = e;
            }
        }

        return winning;
    }

    /**
     * Gets a clone of the cooldown hashmap to avoid multithread madness.
     * todo: Replace with a better solution that doesn't impact memory. (not like this game isn't garbage memory wise anyways)
     */
    private synchronized MMOItemsCooldownEntry[] getCooldownsSafe() {
        return cooldownEntryMap.values().toArray(new MMOItemsCooldownEntry[0]);
    }

    /**
     * Gets a clone of the scheduled packet list to avoid multithread madness.
     * todo: Replace with a better solution that doesn't impact memory. (not like this game isn't garbage memory wise anyways)
     */
    private synchronized ScheduledItemUsePacket[] getCooldownPacketsSafe() {
        return scheduledPackets.toArray(new ScheduledItemUsePacket[0]);
    }

    /**
     * Renders a quad on the GUI. Adapted from ItemRenderer.class
     */
    private void renderGuiQuad(BufferBuilder buffer, int x, int y, int width, int height, int red, int green, int blue, int alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(x + 0, y + 0, 0.0).color(red, green, blue, alpha).next();
        buffer.vertex(x + 0, y + height, 0.0).color(red, green, blue, alpha).next();
        buffer.vertex(x + width, y + height, 0.0).color(red, green, blue, alpha).next();
        buffer.vertex(x + width, y + 0, 0.0).color(red, green, blue, alpha).next();
        buffer.end();
        BufferRenderer.draw(buffer.end());
    }

    record MMOItemsCooldownEntry(int startTick, int endTick) { }
    record ScheduledItemUsePacket(PlayerInteractItemC2SPacket packet, int startTick, int endTick) { }
}
