
package com.example.universalenchant;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import static net.minecraft.server.command.CommandManager.literal;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardCriterion.RenderType;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class UniversalEnchantMod implements ModInitializer {

    public static final String MOD_ID = "universalenchant";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    private static final int INTERVAL_TICKS = 6000;
    private static final int SPIN_TICKS = 60;
    private static final int SPIN_STEP_TICKS = 2;

    private static final String TIMER_OBJECTIVE = "unienchanttime";

    private final Map<UUID, Integer> timers = new HashMap<>();
    private final Map<UUID, SpinState> spinning = new HashMap<>();
    private final Set<UUID> scoreboardShown = new HashSet<>();

    private record SpinState(
            RegistryEntry<Enchantment> result,
            int level,
            int ticksLeft
    ) {
        SpinState withTicksLeft(int ticks) {
            return new SpinState(result, level, ticks);
        }
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Universal Enchantments loaded!");

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        dispatcher.register(
                                literal("enchantroll")
                                        .executes(context -> {
                                            MinecraftServer server =
                                                    context.getSource()
                                                            .getServer();

                                            List<RegistryEntry<Enchantment>>
                                                    enchantments =
                                                    getEnchantments(server);

                                            int started = 0;

                                            for (ServerPlayerEntity player :
                                                    server.getPlayerManager()
                                                            .getPlayerList()) {
                                                if (startSpin(
                                                        player,
                                                        enchantments
                                                )) {
                                                    timers.put(
                                                            player.getUuid(),
                                                            0
                                                    );
                                                    started++;
                                                }
                                            }

                                            final int count = started;
                                            context.getSource()
                                                    .sendFeedback(
                                                            () -> Text.literal(
                                                                    "Started enchantment rolls for "
                                                                            + count
                                                                            + " player(s)!"
                                                            ),
                                                            true
                                                    );

                                            return started;
                                        })
                        )
        );

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<RegistryEntry<Enchantment>> enchantments =
                    getEnchantments(server);

            if (enchantments.isEmpty()) {
                return;
            }

            ScoreboardObjective objective =
                    getTimerObjective(server);

            for (ServerPlayerEntity player :
                    server.getPlayerManager().getPlayerList()) {

                UUID id = player.getUuid();

                int timer = timers.getOrDefault(id, 0) + 1;

                if (timer >= INTERVAL_TICKS
                        && !spinning.containsKey(id)) {
                    timer = 0;
                    startSpin(player, enchantments);
                }

                timers.put(id, timer);

                // Update the scoreboard once per second.
                if (timer % 20 == 0 || timer == 0) {
                    updateTimerScoreboard(
                            player,
                            objective,
                            INTERVAL_TICKS - timer
                    );
                }

                SpinState state = spinning.get(id);

                if (state == null) {
                    continue;
                }

                int left = state.ticksLeft();

                // Roulette animation.
                if (left > 0 && left % SPIN_STEP_TICKS == 0) {
                    RegistryEntry<Enchantment> preview =
                            enchantments.get(
                                    Random.create().nextInt(
                                            enchantments.size()
                                    )
                            );

                    player.networkHandler.sendPacket(
                            new TitleS2CPacket(
                                    Enchantment.getName(preview, 1)
                                            .copy()
                                            .formatted(
                                                    Formatting.YELLOW,
                                                    Formatting.BOLD
                                            )
                            )
                    );

                    player.networkHandler.sendPacket(
                            new SubtitleS2CPacket(
                                    Text.literal("Rolling...")
                                            .formatted(Formatting.GRAY)
                            )
                    );
                }

                left--;

                if (left <= 0) {
                    ItemStack stack = player.getMainHandStack();

                    if (!stack.isEmpty()) {
                        ItemEnchantmentsComponent current =
                                stack.getOrDefault(
                                        DataComponentTypes.ENCHANTMENTS,
                                        ItemEnchantmentsComponent.DEFAULT
                                );

                        ItemEnchantmentsComponent.Builder builder =
                                new ItemEnchantmentsComponent.Builder(
                                        current
                                );

                        builder.set(
                                state.result(),
                                state.level()
                        );

                        stack.set(
                                DataComponentTypes.ENCHANTMENTS,
                                builder.build()
                        );

                        player.networkHandler.sendPacket(
                                new TitleFadeS2CPacket(5, 40, 10)
                        );

                        player.networkHandler.sendPacket(
                                new TitleS2CPacket(
                                        Enchantment.getName(
                                                state.result(),
                                                state.level()
                                        ).copy().formatted(
                                                Formatting.GREEN,
                                                Formatting.BOLD
                                        )
                                )
                        );

                        player.networkHandler.sendPacket(
                                new SubtitleS2CPacket(
                                        Text.literal(
                                                "Level " + state.level()
                                                        + " - applied to your held item!"
                                        ).formatted(Formatting.AQUA)
                                )
                        );
                    } else {
                        player.networkHandler.sendPacket(
                                new TitleS2CPacket(
                                        Text.literal("No item in hand!")
                                                .formatted(
                                                        Formatting.RED,
                                                        Formatting.BOLD
                                                )
                                )
                        );

                        player.networkHandler.sendPacket(
                                new SubtitleS2CPacket(
                                        Text.literal(
                                                "The enchantment was not applied."
                                        ).formatted(Formatting.GRAY)
                                )
                        );
                    }

                    spinning.remove(id);
                } else {
                    spinning.put(
                            id,
                            state.withTicksLeft(left)
                    );
                }
            }

            timers.keySet().removeIf(
                    id -> server.getPlayerManager().getPlayer(id) == null
            );

            spinning.keySet().removeIf(
                    id -> server.getPlayerManager().getPlayer(id) == null
            );

            scoreboardShown.removeIf(
                    id -> server.getPlayerManager().getPlayer(id) == null
            );
        });
    }

    private List<RegistryEntry<Enchantment>> getEnchantments(
            MinecraftServer server
    ) {
        return server.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .streamEntries()
                .map(entry -> (RegistryEntry<Enchantment>) entry)
                .toList();
    }

    private ScoreboardObjective getTimerObjective(
            MinecraftServer server
    ) {
        Scoreboard scoreboard = server.getScoreboard();

        ScoreboardObjective objective =
                scoreboard.getNullableObjective(TIMER_OBJECTIVE);

        if (objective == null) {
            objective = scoreboard.addObjective(
                    TIMER_OBJECTIVE,
                    ScoreboardCriterion.DUMMY,
                    Text.literal("ENCHANT ROULETTE")
                            .formatted(
                                    Formatting.LIGHT_PURPLE,
                                    Formatting.BOLD
                            ),
                    RenderType.INTEGER,
                    false,
                    null
            );
        }

        return objective;
    }

    private void updateTimerScoreboard(
            ServerPlayerEntity player,
            ScoreboardObjective objective,
            int ticksLeft
    ) {
        UUID id = player.getUuid();

        if (scoreboardShown.add(id)) {
            player.networkHandler.sendPacket(
                    new ScoreboardObjectiveUpdateS2CPacket(
                            objective,
                            ScoreboardObjectiveUpdateS2CPacket.ADD_MODE
                    )
            );

            player.networkHandler.sendPacket(
                    new ScoreboardDisplayS2CPacket(
                            ScoreboardDisplaySlot.SIDEBAR,
                            objective
                    )
            );
        }

        int seconds = Math.max(0, ticksLeft / 20);
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;

        String time = String.format(
                java.util.Locale.ROOT,
                "Time Left: %d:%02d",
                minutes,
                remainingSeconds
        );

        player.networkHandler.sendPacket(
                new ScoreboardScoreUpdateS2CPacket(
                        "timerline",
                        TIMER_OBJECTIVE,
                        1,
                        Optional.of(
                                Text.literal(time)
                                        .formatted(Formatting.AQUA)
                        ),
                        Optional.empty()
                )
        );
    }

    private boolean startSpin(
            ServerPlayerEntity player,
            List<RegistryEntry<Enchantment>> enchantments
    ) {
        UUID id = player.getUuid();

        if (spinning.containsKey(id) || enchantments.isEmpty()) {
            return false;
        }

        Random random = Random.create();

        RegistryEntry<Enchantment> result =
                enchantments.get(
                        random.nextInt(enchantments.size())
                );

        int level = 1 + random.nextInt(5);

        spinning.put(
                id,
                new SpinState(
                        result,
                        level,
                        SPIN_TICKS
                )
        );

        player.networkHandler.sendPacket(
                new TitleFadeS2CPacket(0, 10, 5)
        );

        player.networkHandler.sendPacket(
                new TitleS2CPacket(
                        Text.literal("RANDOM ENCHANTMENT")
                                .formatted(
                                        Formatting.LIGHT_PURPLE,
                                        Formatting.BOLD
                                )
                )
        );

        player.networkHandler.sendPacket(
                new SubtitleS2CPacket(
                        Text.literal("Rolling...")
                                .formatted(Formatting.GRAY)
                )
        );

        return true;
    }
}
