package com.subhan.crazyrtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class CrazyRTP extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, VRSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> teleportingPlayers = ConcurrentHashMap.newKeySet();

    private NamespacedKey vrEntityKey;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.vrEntityKey = new NamespacedKey(this, "crazyrtp_vr_entity");

        Objects.requireNonNull(getCommand("rtp")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        cleanupOrphanedEntities();
        startVRMenuTickTask();

        getLogger().info("CrazyRTP v2.0 by Subhan enabled! (1.21 - 26.2 + GeyserMC Ready)");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        for (UUID uuid : new ArrayList<>(activeSessions.keySet())) {
            closeVRMenu(uuid);
        }
        for (UUID uuid : new ArrayList<>(activeWarmups.keySet())) {
            cancelWarmup(uuid, false);
        }
        cleanupOrphanedEntities();
    }

    private void cleanupOrphanedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(vrEntityKey, PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
    }

    public boolean isBedrockPlayer(Player player) {
        // Floodgate / GeyserMC UUIDs start with 00000000-0000-0000
        return player.getUniqueId().toString().startsWith("00000000-0000-0000")
                || player.getName().startsWith(".");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crazyrtp.admin")) {
                sender.sendMessage(prefix().append(Component.text("You do not have permission to reload.", NamedTextColor.RED)));
                return true;
            }
            reloadConfig();
            sender.sendMessage(prefix().append(Component.text("CrazyRTP configuration reloaded successfully!", NamedTextColor.GREEN)));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use CrazyRTP.");
            return true;
        }

        if (!player.hasPermission("crazyrtp.use")) {
            player.sendMessage(prefix().append(Component.text("You do not have permission to use RTP.", NamedTextColor.RED)));
            return true;
        }

        if (teleportingPlayers.contains(player.getUniqueId()) || activeWarmups.containsKey(player.getUniqueId())) {
            player.sendMessage(prefix().append(Component.text("Teleport is already in progress! Stand still.", NamedTextColor.YELLOW)));
            return true;
        }

        // Direct subcommands for quick access or Bedrock binds (/rtp overworld, /rtp nether, /rtp end)
        if (args.length > 0) {
            String sub = args[0].toUpperCase(Locale.ROOT);
            if (sub.equals("OVERWORLD")) {
                triggerDimensionRTP(player, RTPDimension.OVERWORLD, "OVERWORLD", NamedTextColor.GREEN);
                return true;
            } else if (sub.equals("NETHER")) {
                triggerDimensionRTP(player, RTPDimension.NETHER, "THE NETHER", NamedTextColor.RED);
                return true;
            } else if (sub.equals("END")) {
                triggerDimensionRTP(player, RTPDimension.END, "THE END", NamedTextColor.LIGHT_PURPLE);
                return true;
            }
        }

        if (activeSessions.containsKey(player.getUniqueId())) {
            closeVRMenu(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.4f);
            player.sendMessage(prefix().append(Component.text("Closed the VR RTP menu.", NamedTextColor.GRAY)));
            return true;
        }

        openVRMenu(player);
        return true;
    }

    private void openVRMenu(Player player) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().setY(0).normalize();
        if (forward.lengthSquared() < 0.01) {
            forward = new Vector(0, 0, 1);
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        // Positioned 3.1 blocks in front with wide 1.85 block spacing so text NEVER overlaps
        Location centerBase = eye.clone().add(forward.clone().multiply(3.1)).add(0, -0.20, 0);
        float playerYaw = eye.getYaw() + 180f;

        List<VRButton> buttons = new ArrayList<>();
        buttons.add(createButton(player, centerBase.clone().add(right.clone().multiply(-1.85)), playerYaw,
                RTPDimension.OVERWORLD, Material.GRASS_BLOCK, "OVERWORLD", NamedTextColor.GREEN, Color.LIME));

        buttons.add(createButton(player, centerBase.clone(), playerYaw,
                RTPDimension.NETHER, Material.NETHERRACK, "THE NETHER", NamedTextColor.RED, Color.RED));

        buttons.add(createButton(player, centerBase.clone().add(right.clone().multiply(1.85)), playerYaw,
                RTPDimension.END, Material.OBSIDIAN, "THE END", NamedTextColor.LIGHT_PURPLE, Color.PURPLE));

        // Header placed high above (+1.35 Y) to prevent any collision with button labels
        Location headerLoc = centerBase.clone().add(0, 1.35, 0);
        TextDisplay header = spawnPrivateEntity(player, headerLoc, TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(180, 12, 16, 32));
            td.setShadowed(true);
            td.setLineWidth(260);
            td.text(Component.text("✦ CRAZY RTP MENU ✦", TextColor.color(0xFFAA00), TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text("Click / Tap a Block • Sneak to Close", NamedTextColor.GRAY)));
            td.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(),
                    new Vector3f(0.82f, 0.82f, 0.82f),
                    new AxisAngle4f()
            ));
        });

        VRSession session = new VRSession(player.getUniqueId(), player.getLocation().clone(), buttons, header, System.currentTimeMillis());
        activeSessions.put(player.getUniqueId(), session);

        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);
    }

    private VRButton createButton(Player player, Location loc, float yaw, RTPDimension dimension,
                                  Material material, String title, NamedTextColor textColor, Color glowColor) {
        loc.setYaw(yaw);
        loc.setPitch(0f);

        BlockDisplay blockDisplay = spawnPrivateEntity(player, loc, BlockDisplay.class, bd -> {
            bd.setBlock(Bukkit.createBlockData(material));
            bd.setGlowing(false);
            bd.setGlowColorOverride(glowColor);
            bd.setInterpolationDuration(3);
            bd.setInterpolationDelay(0);
            bd.setTransformation(new Transformation(
                    new Vector3f(-0.23f, -0.23f, -0.23f),
                    new AxisAngle4f(),
                    new Vector3f(0.46f, 0.46f, 0.46f),
                    new AxisAngle4f()
            ));
        });

        // Clean compact label above the block (+0.62 Y) with smaller scale (0.68f) so labels never touch
        TextDisplay labelDisplay = spawnPrivateEntity(player, loc.clone().add(0, 0.62, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(165, 15, 15, 25));
            td.setShadowed(true);
            td.setLineWidth(180);
            td.text(buildButtonLabel(title, textColor, false));
            td.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(),
                    new Vector3f(0.68f, 0.68f, 0.68f),
                    new AxisAngle4f()
            ));
        });

        // Generous hitbox for both Java Mouse Clicks and Geyser/Bedrock Mobile Touch Taps
        Interaction interaction = spawnPrivateEntity(player, loc.clone().add(0, -0.45, 0), Interaction.class, inter -> {
            inter.setInteractionWidth(1.10f);
            inter.setInteractionHeight(1.25f);
            inter.setResponsive(true);
            // Custom name helps GeyserMC / Bedrock Edition clients see the label cleanly if TextDisplay is offset
            inter.customName(Component.text("• " + title + " •", textColor, TextDecoration.BOLD));
            inter.setCustomNameVisible(isBedrockPlayer(player));
        });

        return new VRButton(dimension, title, textColor, loc.clone(), blockDisplay, labelDisplay, interaction);
    }

    private Component buildButtonLabel(String title, NamedTextColor color, boolean hovered) {
        if (hovered) {
            return Component.text("» " + title + " «", color, TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text("[ CLICK / TAP ]", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        return Component.text(title, color, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Select", NamedTextColor.GRAY));
    }

    private <T extends Entity> T spawnPrivateEntity(Player owner, Location loc, Class<T> clazz, java.util.function.Consumer<T> function) {
        return loc.getWorld().spawn(loc, clazz, entity -> {
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            entity.getPersistentDataContainer().set(vrEntityKey, PersistentDataType.BYTE, (byte) 1);
            function.accept(entity);
            owner.showEntity(this, entity);
        });
    }

    private void startVRMenuTickTask() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long timeoutMs = getConfig().getLong("settings.menu-timeout-seconds", 30L) * 1000L;

                for (Map.Entry<UUID, VRSession> entry : new ArrayList<>(activeSessions.entrySet())) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    VRSession session = entry.getValue();

                    if (player == null || !player.isOnline() || player.isDead()
                            || !player.getWorld().equals(session.origin.getWorld())
                            || player.getLocation().distanceSquared(session.origin) > 16.0
                            || (now - session.createdAt) > timeoutMs) {
                        closeVRMenu(entry.getKey());
                        continue;
                    }

                    VRButton hovered = getHoveredButton(player, session);
                    for (VRButton btn : session.buttons) {
                        boolean isHover = (btn == hovered);
                        if (btn.hovered != isHover) {
                            btn.hovered = isHover;
                            updateButtonVisual(player, btn, isHover);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    private VRButton getHoveredButton(Player player, VRSession session) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        VRButton best = null;
        // Slightly wider cone for Bedrock/PE players so tapping is super easy
        double bestDot = isBedrockPlayer(player) ? 0.940 : 0.968;

        for (VRButton btn : session.buttons) {
            Vector toBtn = btn.center.toVector().subtract(eye.toVector()).normalize();
            double dot = dir.dot(toBtn);
            if (dot > bestDot) {
                bestDot = dot;
                best = btn;
            }
        }
        return best;
    }

    private void updateButtonVisual(Player player, VRButton btn, boolean hovered) {
        if (btn.blockDisplay.isValid()) {
            float scale = hovered ? 0.64f : 0.46f;
            float offset = -scale / 2.0f;
            btn.blockDisplay.setGlowing(hovered);
            btn.blockDisplay.setInterpolationDelay(0);
            btn.blockDisplay.setInterpolationDuration(3);
            btn.blockDisplay.setTransformation(new Transformation(
                    new Vector3f(offset, offset, offset),
                    new AxisAngle4f(),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()
            ));
        }
        if (btn.labelDisplay.isValid()) {
            btn.labelDisplay.text(buildButtonLabel(btn.title, btn.color, hovered));
        }
        if (hovered) {
            player.playSound(btn.center, Sound.UI_BUTTON_CLICK, 0.4f, 1.8f);
            player.spawnParticle(Particle.END_ROD, btn.center, 4, 0.15, 0.15, 0.15, 0.01);
        }
    }

    private void handleButtonClick(Player player, VRButton button) {
        closeVRMenu(player.getUniqueId());
        player.spawnParticle(Particle.PORTAL, button.center, 25, 0.25, 0.25, 0.25, 0.2);
        triggerDimensionRTP(player, button.dimension, button.title, button.color);
    }

    private void triggerDimensionRTP(Player player, RTPDimension dimension, String title, NamedTextColor color) {
        if (!player.hasPermission("crazyrtp.bypass.cooldown")) {
            long now = System.currentTimeMillis();
            long expiresAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < expiresAt) {
                long left = (expiresAt - now) / 1000L + 1L;
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                player.sendMessage(prefix().append(Component.text("Wait " + left + "s before using RTP again!", NamedTextColor.RED)));
                return;
            }
        }

        String worldName = getConfig().getString("worlds." + dimension.name() + ".world-name");
        World targetWorld = (worldName != null) ? Bukkit.getWorld(worldName) : null;
        if (targetWorld == null) {
            targetWorld = findWorldByEnvironment(dimension.environment);
        }

        if (targetWorld == null) {
            player.sendMessage(prefix().append(Component.text("Target world for " + title + " is not loaded!", NamedTextColor.RED)));
            return;
        }

        int warmupSeconds = getConfig().getInt("settings.warmup-seconds", 5);
        if (warmupSeconds <= 0 || player.hasPermission("crazyrtp.bypass.timer")) {
            startSafeTeleportSearch(player, targetWorld, dimension, title, color);
        } else {
            startWarmupCountdown(player, targetWorld, dimension, title, color, warmupSeconds);
        }
    }

    private void startWarmupCountdown(Player player, World targetWorld, RTPDimension dim,
                                      String title, NamedTextColor color, int seconds) {
        cancelWarmup(player.getUniqueId(), false);

        Location startLoc = player.getLocation().clone();
        player.sendMessage(prefix().append(Component.text("Teleporting to ", NamedTextColor.GRAY))
                .append(Component.text(title, color, TextDecoration.BOLD))
                .append(Component.text(" in " + seconds + " seconds! Do not move!", NamedTextColor.YELLOW)));

        BukkitTask task = new BukkitRunnable() {
            int timeLeft = seconds;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancelWarmup(player.getUniqueId(), false);
                    return;
                }

                // Check if player moved from their starting spot
                if (!player.getWorld().equals(startLoc.getWorld())
                        || player.getLocation().distanceSquared(startLoc) > 0.06) {
                    cancelWarmup(player.getUniqueId(), true);
                    return;
                }

                if (timeLeft <= 0) {
                    activeWarmups.remove(player.getUniqueId());
                    cancel();
                    startSafeTeleportSearch(player, targetWorld, dim, title, color);
                    return;
                }

                Title countdownTitle = Title.title(
                        Component.text("⏳ " + timeLeft + "s", color, TextDecoration.BOLD),
                        Component.text("Don't move! Teleporting to " + title, NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1150), Duration.ofMillis(200))
                );
                player.showTitle(countdownTitle);
                player.sendActionBar(Component.text("⚡ CrazyRTP Warmup: " + timeLeft + "s remaining (Do Not Move!)", NamedTextColor.GOLD));

                float pitch = 1.0f + ((seconds - timeLeft) * 0.15f);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, pitch);
                player.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1.0, 0), 20, 0.4, 0.6, 0.4, 0.15);

                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L);

        activeWarmups.put(player.getUniqueId(), new WarmupSession(startLoc, task));
    }

    private void cancelWarmup(UUID uuid, boolean notifyPlayer) {
        WarmupSession warmup = activeWarmups.remove(uuid);
        if (warmup == null) return;
        warmup.task.cancel();

        if (notifyPlayer) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.9f);
                Title cancelTitle = Title.title(
                        Component.text("✖ CANCELLED!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("You moved! Teleport was cancelled.", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1800), Duration.ofMillis(400))
                );
                player.showTitle(cancelTitle);
                player.sendActionBar(Component.text("✖ Teleport cancelled because you moved!", NamedTextColor.RED));
                player.sendMessage(prefix().append(Component.text("Teleport cancelled because you moved!", NamedTextColor.RED)));
            }
        }
    }

    private void startSafeTeleportSearch(Player player, World targetWorld, RTPDimension dim,
                                         String title, NamedTextColor color) {
        teleportingPlayers.add(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.5f);
        player.sendActionBar(Component.text("🔍 Finding a safe location in " + title + "...", NamedTextColor.AQUA));

        int maxAttempts = getConfig().getInt("settings.max-search-attempts", 12);
        findSafeLocationAsync(player, targetWorld, dim, title, color, 1, maxAttempts);
    }

    private World findWorldByEnvironment(World.Environment env) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    private void findSafeLocationAsync(Player player, World world, RTPDimension dim, String title,
                                       NamedTextColor color, int attempt, int maxAttempts) {
        if (!player.isOnline()) {
            teleportingPlayers.remove(player.getUniqueId());
            return;
        }

        if (attempt > maxAttempts) {
            teleportingPlayers.remove(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            player.sendMessage(prefix().append(Component.text("Could not find a safe spot after " + maxAttempts + " tries. Please try again!", NamedTextColor.RED)));
            return;
        }

        int minRadius = getConfig().getInt("worlds." + dim.name() + ".min-radius", 200);
        int maxRadius = getConfig().getInt("worlds." + dim.name() + ".max-radius", 4000);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble(0, Math.PI * 2);
        double dist = rng.nextDouble(minRadius, Math.max(minRadius + 1, maxRadius));

        Location center = world.getWorldBorder().getCenter();
        int x = (int) Math.round(center.getX() + Math.cos(angle) * dist);
        int z = (int) Math.round(center.getZ() + Math.sin(angle) * dist);

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    teleportingPlayers.remove(player.getUniqueId());
                    return;
                }

                Location safeLoc = findSafeY(world, x, z, dim.environment);
                if (safeLoc != null && world.getWorldBorder().isInside(safeLoc)) {
                    teleportingPlayers.remove(player.getUniqueId());
                    long cooldownSec = getConfig().getLong("settings.cooldown-seconds", 15L);
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (cooldownSec * 1000L));

                    safeLoc.setYaw(player.getLocation().getYaw());
                    safeLoc.setPitch(0f);

                    player.teleportAsync(safeLoc).thenAccept(success -> {
                        if (success) {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4, false, false, true));
                            player.playSound(safeLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.1f);
                            player.playSound(safeLoc, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                            player.spawnParticle(Particle.TOTEM_OF_UNDYING, safeLoc.clone().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.1);

                            Title arrivalTitle = Title.title(
                                    Component.text("✔ TELEPORTED!", color, TextDecoration.BOLD),
                                    Component.text(title + " • X: " + x + " Y: " + safeLoc.getBlockY() + " Z: " + z, NamedTextColor.WHITE),
                                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2400), Duration.ofMillis(500))
                            );
                            player.showTitle(arrivalTitle);
                            player.sendActionBar(Component.text("✔ Safely teleported to " + title + "!", NamedTextColor.GREEN));
                            player.sendMessage(prefix().append(Component.text("Teleported safely to ", NamedTextColor.GREEN))
                                    .append(Component.text(title + " (" + x + ", " + safeLoc.getBlockY() + ", " + z + ")", color)));
                        }
                    });
                } else {
                    findSafeLocationAsync(player, world, dim, title, color, attempt + 1, maxAttempts);
                }
            });
        });
    }

    private Location findSafeY(World world, int x, int z, World.Environment env) {
        if (env == World.Environment.NETHER) {
            for (int y = 110; y >= 32; y--) {
                Block ground = world.getBlockAt(x, y, z);
                Block feet = world.getBlockAt(x, y + 1, z);
                Block head = world.getBlockAt(x, y + 2, z);
                if (isSafeGround(ground.getType()) && isPassableAir(feet.getType()) && isPassableAir(head.getType())) {
                    return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                }
            }
            return null;
        }

        int highestY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (env == World.Environment.THE_END && highestY < 45) {
            return null;
        }
        if (highestY <= world.getMinHeight()) {
            return null;
        }

        Block ground = world.getBlockAt(x, highestY, z);
        Block feet = world.getBlockAt(x, highestY + 1, z);
        Block head = world.getBlockAt(x, highestY + 2, z);

        if (isSafeGround(ground.getType()) && isPassableAir(feet.getType()) && isPassableAir(head.getType())) {
            return new Location(world, x + 0.5, highestY + 1.0, z + 0.5);
        }
        return null;
    }

    private boolean isSafeGround(Material mat) {
        if (!mat.isSolid()) return false;
        return switch (mat) {
            case MAGMA_BLOCK, CACTUS, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                 LAVA, WATER, POWDER_SNOW, POINTED_DRIPSTONE, SWEET_BERRY_BUSH, BEDROCK -> false;
            default -> true;
        };
    }

    private boolean isPassableAir(Material mat) {
        return mat.isAir() || mat == Material.SHORT_GRASS || mat == Material.TALL_GRASS || mat == Material.FERN;
    }

    private void closeVRMenu(UUID uuid) {
        VRSession session = activeSessions.remove(uuid);
        if (session == null) return;

        if (session.header != null && session.header.isValid()) {
            session.header.remove();
        }
        for (VRButton btn : session.buttons) {
            if (btn.blockDisplay.isValid()) btn.blockDisplay.remove();
            if (btn.labelDisplay.isValid()) btn.labelDisplay.remove();
            if (btn.interaction.isValid()) btn.interaction.remove();
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        WarmupSession warmup = activeWarmups.get(uuid);
        if (warmup == null) return;

        Location to = event.getTo();
        if (to == null) return;

        // Ignore head rotation (mouse look), only cancel if X/Y/Z coordinates changed
        if ( to.getWorld() != warmup.startLoc.getWorld()
                || to.distanceSquared(warmup.startLoc) > 0.05) {
            cancelWarmup(uuid, true);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (activeWarmups.containsKey(player.getUniqueId())) {
                cancelWarmup(player.getUniqueId(), true);
            }
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        VRSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        for (VRButton btn : session.buttons) {
            if (btn.interaction.equals(event.getRightClicked())) {
                event.setCancelled(true);
                handleButtonClick(player, btn);
                return;
            }
        }
    }

    @EventHandler
    public void onEntityAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        VRSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        for (VRButton btn : session.buttons) {
            if (btn.interaction.equals(event.getEntity())) {
                event.setCancelled(true);
                handleButtonClick(player, btn);
                return;
            }
        }
    }

    @EventHandler
    public void onAirClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        VRSession session = activeSessions.get(player.getUniqueId());
        if (session == null) return;

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.RIGHT_CLICK_AIR
                || action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK) {
            VRButton hovered = getHoveredButton(player, session);
            if (hovered != null) {
                event.setCancelled(true);
                handleButtonClick(player, hovered);
            }
        }
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && activeSessions.containsKey(event.getPlayer().getUniqueId())) {
            closeVRMenu(event.getPlayer().getUniqueId());
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        closeVRMenu(uuid);
        cancelWarmup(uuid, false);
        teleportingPlayers.remove(uuid);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        closeVRMenu(uuid);
        cancelWarmup(uuid, false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        closeVRMenu(uuid);
        cancelWarmup(uuid, false);
    }

    private Component prefix() {
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("CrazyRTP", TextColor.color(0xFFAA00), TextDecoration.BOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));
    }

    private enum RTPDimension {
        OVERWORLD(World.Environment.NORMAL),
        NETHER(World.Environment.NETHER),
        END(World.Environment.THE_END);

        final World.Environment environment;
        RTPDimension(World.Environment environment) {
            this.environment = environment;
        }
    }

    private static final class WarmupSession {
        final Location startLoc;
        final BukkitTask task;

        WarmupSession(Location startLoc, BukkitTask task) {
            this.startLoc = startLoc;
            this.task = task;
        }
    }

    private static final class VRButton {
        final RTPDimension dimension;
        final String title;
        final NamedTextColor color;
        final Location center;
        final BlockDisplay blockDisplay;
        final TextDisplay labelDisplay;
        final Interaction interaction;
        boolean hovered = false;

        VRButton(RTPDimension dimension, String title, NamedTextColor color, Location center,
                 BlockDisplay blockDisplay, TextDisplay labelDisplay, Interaction interaction) {
            this.dimension = dimension;
            this.title = title;
            this.color = color;
            this.center = center;
            this.blockDisplay = blockDisplay;
            this.labelDisplay = labelDisplay;
            this.interaction = interaction;
        }
    }

    private static final class VRSession {
        final UUID playerUuid;
        final Location origin;
        final List<VRButton> buttons;
        final TextDisplay header;
        final long createdAt;

        VRSession(UUID playerUuid, Location origin, List<VRButton> buttons, TextDisplay header, long createdAt) {
            this.playerUuid = playerUuid;
            this.origin = origin;
            this.buttons = buttons;
            this.header = header;
            this.createdAt = createdAt;
        }
    }
}
