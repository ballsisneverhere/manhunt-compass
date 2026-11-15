package dev.manhunt;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manhunt tracking compass.
 *
 * How it works: the server always knows where the speedrunner is, even if the hunter is
 * 50,000 blocks away and no chunks are loaded around the runner. We store the runner's
 * position per dimension and point a "lodestone compass" (lodestoneTracked = false, so no
 * lodestone block has to exist or be loaded) at that position. If the runner is in a
 * different dimension than the hunter, the compass points to the last place the runner
 * was in the hunter's dimension (i.e. the portal they used), like vanilla manhunt.
 */
public final class ManhuntPlugin extends JavaPlugin implements Listener, TabExecutor {

    /** shown in the console on start and by /manhunt version, to check the right jar is installed */
    private static final String BUILD = "no-respawn-freeze-1";

    private NamespacedKey trackerKey;

    private final Set<UUID> hunters = new HashSet<>();
    private UUID runnerId;
    private String runnerName = "?";

    /** true from the end of the /manhunt start countdown until someone wins (or /manhunt reset) */
    private boolean roundActive = false;
    private long roundStart = 0L;

    /** world UID -> last known runner location in that world */
    private final Map<UUID, Location> lastKnown = new HashMap<>();

    /** players who can't move or break blocks during the start countdown */
    private final Set<UUID> frozen = new HashSet<>();
    private BukkitTask countdownTask;
    private BukkitTask breakTask;

    /** random spot for the next round, picked at the start of the break so its chunks can be loaded early */
    private Location nextSpot;
    private BukkitTask preloadTask;
    private World ticketedWorld;
    private final List<int[]> ticketedChunks = new ArrayList<>();

    /** portal blocks created during the game ("worldUID;x;y;z"), so they can be removed again */
    private final Set<String> netherPortalBlocks = new HashSet<>();
    /** end portal frames that got an eye from a player */
    private final Set<String> eyeFrames = new HashSet<>();
    /** the dragon was killed (runner won): it comes back when the first player enters the End next round */
    private boolean dragonRespawnPending = false;
    private boolean endResetScheduled = false;
    /** /manhunt resetend was used while nobody was in the End: do the full manual reset on the next visit */
    private boolean endResetManualRequested = false;

    /** somebody was in the End during this round, so crystals may have been broken */
    private boolean endVisited = false;

    /** true while the plugin itself kills the old dragon, so that death does not count as a runner win */
    private boolean ignoreDragonDeath = false;

    /** where the current round started (new hunters respawn here instead of the world spawn) */
    private Location roundSpawn;
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** win stats, saved in data.yml */
    private int runnerWins = 0;
    private int hunterWins = 0;
    private File dataFile;

    private static final int COUNTDOWN_SECONDS = 5;
    private static final double CIRCLE_RADIUS = 5.0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        trackerKey = new NamespacedKey(this, "tracker");
        getServer().getPluginManager().registerEvents(this, this);

        for (String name : new String[]{"hunter", "speedrunner", "manhunt"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        long interval = Math.max(1L, getConfig().getLong("update-interval-ticks", 10L));
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, interval);
        getLogger().info("ManhuntCompass (build " + BUILD + ") enabled. Use /speedrunner <player> and /hunter <player>.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------ saving

    /** Saves runner, hunters, round state and last known runner positions so they survive restarts. */
    private void saveData() {
        if (dataFile == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("runner", runnerId == null ? null : runnerId.toString());
        y.set("runnerName", runnerName);
        List<String> ids = new ArrayList<>();
        for (UUID id : hunters) {
            ids.add(id.toString());
        }
        y.set("hunters", ids);
        y.set("roundActive", roundActive);
        y.set("roundStart", roundStart);
        y.set("stats.runnerWins", runnerWins);
        y.set("stats.hunterWins", hunterWins);
        y.set("portals.nether", new ArrayList<>(netherPortalBlocks));
        y.set("portals.frames", new ArrayList<>(eyeFrames));
        y.set("dragonPending", dragonRespawnPending);
        if (roundSpawn != null && roundSpawn.getWorld() != null) {
            y.set("roundSpawn.world", roundSpawn.getWorld().getUID().toString());
            y.set("roundSpawn.x", roundSpawn.getX());
            y.set("roundSpawn.y", roundSpawn.getY());
            y.set("roundSpawn.z", roundSpawn.getZ());
        }
        for (Map.Entry<UUID, Location> en : lastKnown.entrySet()) {
            Location l = en.getValue();
            String base = "lastKnown." + en.getKey();
            y.set(base + ".x", l.getX());
            y.set(base + ".y", l.getY());
            y.set(base + ".z", l.getZ());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);

        String r = y.getString("runner");
        if (r != null) {
            try {
                runnerId = UUID.fromString(r);
            } catch (IllegalArgumentException ignored) {
                runnerId = null;
            }
        }
        runnerName = y.getString("runnerName", "?");

        for (String id : y.getStringList("hunters")) {
            try {
                hunters.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // skip bad entry
            }
        }

        roundActive = y.getBoolean("roundActive", false);
        roundStart = y.getLong("roundStart", System.currentTimeMillis());
        runnerWins = y.getInt("stats.runnerWins", 0);
        hunterWins = y.getInt("stats.hunterWins", 0);
        netherPortalBlocks.addAll(y.getStringList("portals.nether"));
        eyeFrames.addAll(y.getStringList("portals.frames"));
        dragonRespawnPending = y.getBoolean("dragonPending", false);
        String spawnWorld = y.getString("roundSpawn.world");
        if (spawnWorld != null) {
            try {
                World sw = Bukkit.getWorld(UUID.fromString(spawnWorld));
                if (sw != null) {
                    roundSpawn = new Location(sw, y.getDouble("roundSpawn.x"),
                            y.getDouble("roundSpawn.y"), y.getDouble("roundSpawn.z"));
                }
            } catch (IllegalArgumentException ignored) {
                // skip bad entry
            }
        }

        ConfigurationSection sec = y.getConfigurationSection("lastKnown");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    World w = Bukkit.getWorld(UUID.fromString(key));
                    if (w == null) continue;
                    lastKnown.put(w.getUID(), new Location(w,
                            sec.getDouble(key + ".x"), sec.getDouble(key + ".y"), sec.getDouble(key + ".z")));
                } catch (IllegalArgumentException ignored) {
                    // skip bad entry
                }
            }
        }
        if (runnerId != null || !hunters.isEmpty()) {
            getLogger().info("Loaded saved manhunt roles: " + hunters.size() + " hunter(s), runner "
                    + (runnerId == null ? "none" : runnerName));
        }
    }

    // ------------------------------------------------------------------ tracking

    private void tick() {
        Player runner = getRunner();
        if (runner != null) {
            remember(runner.getLocation());
        }
        for (UUID id : hunters) {
            Player hunter = Bukkit.getPlayer(id);
            if (hunter != null) {
                refresh(hunter, runner, true);
            }
        }
    }

    private Player getRunner() {
        return runnerId == null ? null : Bukkit.getPlayer(runnerId);
    }

    private void remember(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        lastKnown.put(loc.getWorld().getUID(), loc.clone());
    }

    /** Point the hunter's tracker compass(es) and optionally show the action bar. */
    private void refresh(Player hunter, Player runner, boolean actionBar) {
        Location target = lastKnown.get(hunter.getWorld().getUID());
        if (target != null) {
            pointCompasses(hunter, target);
        }
        if (actionBar && holdingTracker(hunter) && !frozen.contains(hunter.getUniqueId())) {
            showInfo(hunter, runner, target);
        }
    }

    private void pointCompasses(Player hunter, Location target) {
        PlayerInventory inv = hunter.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isTracker(item)) continue;

            CompassMeta meta = (CompassMeta) item.getItemMeta();
            if (meta == null) continue;

            Location current = meta.getLodestone();
            if (current != null
                    && current.getWorld() != null
                    && current.getWorld().equals(target.getWorld())
                    && current.getBlockX() == target.getBlockX()
                    && current.getBlockY() == target.getBlockY()
                    && current.getBlockZ() == target.getBlockZ()) {
                continue; // already pointing there
            }

            meta.setLodestone(target);
            meta.setLodestoneTracked(false); // no lodestone block needed -> works at any distance / unloaded chunks
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
    }

    private void showInfo(Player hunter, Player runner, Location target) {
        String msg;
        if (runnerId == null) {
            msg = "§cNo speedrunner set - use /speedrunner <player>";
        } else if (runner == null) {
            msg = "§6" + runnerName + " §c(offline)";
            if (target != null) {
                msg += " §7| §fLast known: " + coords(target) + " §7| §e" + dist(hunter, target) + " blocks";
            }
        } else if (runner.getWorld().equals(hunter.getWorld())) {
            Location rl = runner.getLocation();
            msg = "§6" + runnerName + " §7| §a" + dimension(rl.getWorld())
                    + " §7| §f" + coords(rl) + " §7| §e" + dist(hunter, rl) + " blocks";
        } else {
            Location rl = runner.getLocation();
            msg = "§6" + runnerName + " §7is in the §c" + dimension(rl.getWorld())
                    + " §7(" + coords(rl) + ")";
            if (target != null) {
                msg += " §7| compass -> last spot here §f" + coords(target);
            } else {
                msg += " §7| not been in this dimension yet";
            }
        }
        hunter.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
    }

    private static String dimension(World w) {
        return switch (w.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> w.getName();
        };
    }

    private static String coords(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static long dist(Player hunter, Location target) {
        return Math.round(hunter.getLocation().distance(target));
    }

    // ------------------------------------------------------------------ compass item

    private boolean isTracker(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(trackerKey, PersistentDataType.BYTE);
    }

    private boolean holdingTracker(Player p) {
        return isTracker(p.getInventory().getItemInMainHand()) || isTracker(p.getInventory().getItemInOffHand());
    }

    private boolean hasTracker(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTracker(it)) return true;
        }
        return false;
    }

    private void giveCompass(Player p) {
        if (hasTracker(p)) return;

        ItemStack item = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName("§c§lSpeedrunner Tracker");
        meta.setLore(List.of("§7Points to the speedrunner.", "§7Right-click to refresh."));
        meta.getPersistentDataContainer().set(trackerKey, PersistentDataType.BYTE, (byte) 1);

        Location target = lastKnown.get(p.getWorld().getUID());
        meta.setLodestone(target != null ? target : p.getLocation());
        meta.setLodestoneTracked(false);
        item.setItemMeta(meta);

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), extra);
        }
    }

    private void removeTrackers(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isTracker(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "hunter":
                return cmdHunter(sender, args);
            case "speedrunner":
                return cmdSpeedrunner(sender, args);
            case "manhunt":
                return cmdManhunt(sender, args);
            default:
                return false;
        }
    }

    private Player findPlayer(CommandSender sender, String raw) {
        String name = raw.replace("\"", "").replace("'", "");
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            sender.sendMessage("§cPlayer '" + name + "' is not online.");
        }
        return p;
    }

    private boolean cmdHunter(CommandSender sender, String[] args) {
        if (args.length < 1) return false;
        Player target = findPlayer(sender, args[0]);
        if (target == null) return true;

        if (target.getUniqueId().equals(runnerId)) {
            sender.sendMessage("§c" + target.getName() + " is the speedrunner, they can't be a hunter.");
            return true;
        }

        becomeHunter(target);
        sender.sendMessage("§a" + target.getName() + " is now a hunter.");
        return true;
    }

    private boolean cmdSpeedrunner(CommandSender sender, String[] args) {
        if (args.length < 1) return false;
        Player target = findPlayer(sender, args[0]);
        if (target == null) return true;

        becomeRunner(target);
        sender.sendMessage("§a" + target.getName() + " is now the speedrunner. §7Use §f/manhunt start §7to begin.");
        return true;
    }

    private void becomeHunter(Player target) {
        hunters.add(target.getUniqueId());
        giveCompass(target);
        refresh(target, getRunner(), false);
        target.sendMessage("§cYou are now a HUNTER! §7Use your compass to track "
                + (runnerId == null ? "the speedrunner." : runnerName + "."));
        saveData();
    }

    private void becomeRunner(Player target) {
        runnerId = target.getUniqueId();
        runnerName = target.getName();
        hunters.remove(runnerId);
        removeTrackers(target);
        roundActive = false; // the round begins when /manhunt start finishes

        lastKnown.clear();
        remember(target.getLocation());

        target.sendMessage("§aYou are the SPEEDRUNNER! §7Hunters are tracking you.");
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                giveCompass(h);
                refresh(h, target, false);
                h.sendMessage("§6" + runnerName + " §7is now the speedrunner. Your compass tracks them.");
            }
        }
        saveData();
    }

    private static final String MANHUNT_USAGE =
            "§cUsage: /manhunt <start|random|remove <player>|stats|resetportals|resetend|status|reset|compass>";

    private boolean cmdManhunt(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(MANHUNT_USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status": {
                List<String> names = new ArrayList<>();
                for (UUID id : hunters) {
                    Player h = Bukkit.getPlayer(id);
                    names.add(h != null ? h.getName() : id.toString().substring(0, 8) + " (offline)");
                }
                sender.sendMessage("§6Speedrunner: §f" + (runnerId == null ? "none" : runnerName));
                sender.sendMessage("§6Hunters: §f" + (names.isEmpty() ? "none" : String.join(", ", names)));
                sender.sendMessage("§6Round: §f" + (roundActive ? "running"
                        : breakTask != null ? "break (next round soon)" : "not started"));
                sender.sendMessage("§6Score: §aRunner wins " + runnerWins + " §7| §cHunter wins " + hunterWins);
                return true;
            }
            case "version": {
                sender.sendMessage("§6ManhuntCompass build: §f" + BUILD);
                return true;
            }
            case "resetend": {
                World end = null;
                for (World w : Bukkit.getWorlds()) {
                    if (w.getEnvironment() == World.Environment.THE_END) {
                        end = w;
                        break;
                    }
                }
                if (end == null) {
                    sender.sendMessage("§cThis server has no End world.");
                    return true;
                }
                dragonRespawnPending = true;
                endResetManualRequested = true;
                if (end.getPlayers().isEmpty()) {
                    saveData();
                    sender.sendMessage("§aNobody is in the End right now, so it will reset 3 seconds after the next player enters it.");
                } else {
                    sender.sendMessage("§aResetting the End...");
                    resetEnd(end, true, sender);
                }
                return true;
            }
            case "resetportals": {
                sender.sendMessage("§a" + resetPortals());
                return true;
            }
            case "stats": {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    runnerWins = 0;
                    hunterWins = 0;
                    saveData();
                    sender.sendMessage("§aStats reset.");
                    return true;
                }
                sender.sendMessage("§6Runner wins: §a" + runnerWins);
                sender.sendMessage("§6Hunter wins: §c" + hunterWins);
                return true;
            }
            case "reset": {
                resetAll();
                sender.sendMessage("§aManhunt reset. All tracker compasses removed.");
                return true;
            }
            case "start": {
                return cmdStart(sender);
            }
            case "random": {
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (online.size() < 2) {
                    sender.sendMessage("§cYou need at least 2 players online.");
                    return true;
                }
                resetAll();
                Player runner = online.get(ThreadLocalRandom.current().nextInt(online.size()));
                becomeRunner(runner);
                for (Player other : online) {
                    if (!other.getUniqueId().equals(runner.getUniqueId())) {
                        becomeHunter(other);
                    }
                }
                Bukkit.broadcastMessage("§6§lRANDOM PICK §7- Speedrunner: §f" + runner.getName()
                        + "§7. Everyone else is a hunter. Use §f/manhunt start §7to begin!");
                return true;
            }
            case "remove": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /manhunt remove <player>");
                    return true;
                }
                String name = args[1].replace("\"", "").replace("'", "");
                Player online = Bukkit.getPlayerExact(name);
                UUID id = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();

                boolean wasHunter = hunters.remove(id);
                boolean wasRunner = id.equals(runnerId);
                if (wasRunner) {
                    runnerId = null;
                    runnerName = "?";
                    lastKnown.clear();
                    roundActive = false;
                }
                frozen.remove(id);
                if (online != null) {
                    removeTrackers(online);
                }

                if (!wasHunter && !wasRunner) {
                    sender.sendMessage("§e" + name + " isn't a hunter or the speedrunner.");
                } else {
                    String role = wasRunner ? "speedrunner" : "hunter";
                    sender.sendMessage("§a" + name + " was removed as " + role + ".");
                    if (online != null) {
                        online.sendMessage("§7You are no longer the " + role + ".");
                    }
                    saveData();
                }
                return true;
            }
            case "compass": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cOnly players can use this.");
                    return true;
                }
                if (!hunters.contains(p.getUniqueId())) {
                    sender.sendMessage("§cYou are not a hunter.");
                    return true;
                }
                giveCompass(p);
                refresh(p, getRunner(), false);
                return true;
            }
            default:
                sender.sendMessage(MANHUNT_USAGE);
                return true;
        }
    }

    // ------------------------------------------------------------------ /manhunt start

    private boolean cmdStart(CommandSender sender) {
        if (breakTask != null) {
            sender.sendMessage("§cThe next round is already scheduled.");
            return true;
        }
        if (countdownTask != null) {
            sender.sendMessage("§cA countdown is already running.");
            return true;
        }
        if (runnerId == null) {
            sender.sendMessage("§cNo speedrunner set. Use /speedrunner <player> or /manhunt random first.");
            return true;
        }
        Player runner = getRunner();
        if (runner == null) {
            sender.sendMessage("§cThe speedrunner is offline.");
            return true;
        }
        List<Player> hs = new ArrayList<>();
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                hs.add(h);
            }
        }
        if (hs.isEmpty()) {
            sender.sendMessage("§cNo hunters are online. Use /hunter <player> first.");
            return true;
        }

        // everybody who plays starts with an empty inventory (hunters get their compass back)
        if (getConfig().getBoolean("clear-inventory", true)) {
            clearInventory(runner);
            runner.sendMessage("§7Your inventory was cleared.");
            for (Player h : hs) {
                clearInventory(h);
                h.sendMessage("§7Your inventory was cleared.");
            }
        }
        for (Player h : hs) {
            giveCompass(h);
            refresh(h, runner, false);
        }

        // the runner stays exactly where they are; the hunters come to them
        gatherAndCountdown(runner, hs, runner.getLocation(), false, COUNTDOWN_SECONDS);
        return true;
    }

    /**
     * Hunters get teleported in a circle around the runner (facing them), everybody is frozen
     * and the countdown runs. If moveRunner is true the runner is teleported to runnerSpot first,
     * otherwise they stay where they are.
     */
    private void gatherAndCountdown(Player runner, List<Player> hs, Location runnerSpot, boolean moveRunner, int seconds) {
        roundActive = false;

        if (moveRunner) {
            teleportFrozen(runner, runnerSpot);
        } else {
            frozen.add(runner.getUniqueId());
        }

        int n = hs.size();
        double[] radii = {CIRCLE_RADIUS, 3.0, 1.5}; // fall back to a tighter circle if the first one is inside rock
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            Location spot = null;
            for (double r : radii) {
                spot = standableNear(runnerSpot,
                        runnerSpot.getX() + r * Math.cos(angle),
                        runnerSpot.getZ() + r * Math.sin(angle));
                if (spot != null) break;
            }
            if (spot == null) {
                spot = runnerSpot.clone();
            }
            spot.setDirection(runnerSpot.toVector().subtract(spot.toVector())); // face the runner
            teleportFrozen(hs.get(i), spot);
        }

        lastKnown.clear();
        remember(runner.getLocation());

        roundSpawn = runnerSpot.clone();
        saveData();

        Bukkit.broadcastMessage("§6§lMANHUNT §7starts in §f" + seconds
                + " §7seconds. Everyone is frozen until then!");
        startCountdown(seconds);
    }

    /**
     * Finds a place to stand at x/z close to the height of "base" (works in caves, the Nether and the End,
     * not just on the surface). Needs solid ground below and 2 free blocks above it. Returns null if none.
     */
    private static Location standableNear(Location base, double x, double z) {
        World w = base.getWorld();
        if (w == null) return null;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int baseY = base.getBlockY();

        for (int d = 0; d <= 10; d++) {
            int[] ys = d == 0 ? new int[]{baseY} : new int[]{baseY + d, baseY - d};
            for (int y : ys) {
                if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
                Block below = w.getBlockAt(bx, y - 1, bz);
                Block feet = w.getBlockAt(bx, y, bz);
                Block head = w.getBlockAt(bx, y + 1, bz);
                if (below.getType().isSolid()
                        && !feet.getType().isSolid() && !feet.isLiquid()
                        && !head.getType().isSolid() && !head.isLiquid()) {
                    return new Location(w, x, y, z);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ next round (after the runner died)

    /** seconds after the end of a round until everybody gets teleported */
    private int teleportDelay() {
        return Math.max(1, getConfig().getInt("teleport-delay-seconds", 5));
    }

    /** seconds everybody waits frozen at the new spot before the round starts */
    private int freezeSeconds() {
        return Math.max(1, getConfig().getInt("freeze-seconds", 50));
    }

    /** total time from the end of a round until the next one starts */
    private int roundBreakTotal() {
        return teleportDelay() + freezeSeconds();
    }

    private void scheduleNextRound() {
        Bukkit.broadcastMessage("§6Next round: teleport in §f" + teleportDelay() + " §6seconds, then everybody waits "
                + "frozen until the round starts (§f" + roundBreakTotal() + " §6seconds from now). "
                + "A new speedrunner will be picked!");
        breakTask = Bukkit.getScheduler().runTaskLater(this, this::startNextRound, teleportDelay() * 20L);
        preloadNextSpot();
    }

    /** Picks the random spot now and loads the chunks around it a little at a time during the break. */
    private void preloadNextSpot() {
        releasePreloadedChunks();
        World world = Bukkit.getWorlds().get(0);
        nextSpot = randomSafeCenter(world);
        ticketedWorld = world;

        final int cx = nextSpot.getBlockX() >> 4;
        final int cz = nextSpot.getBlockZ() >> 4;
        final List<int[]> queue = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                queue.add(new int[]{cx + dx, cz + dz});
            }
        }
        queue.sort(Comparator.comparingInt((int[] c) -> (c[0] - cx) * (c[0] - cx) + (c[1] - cz) * (c[1] - cz)));

        final int[] index = {0};
        preloadTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (index[0] >= queue.size()) {
                if (preloadTask != null) {
                    preloadTask.cancel();
                    preloadTask = null;
                }
                return;
            }
            int[] c = queue.get(index[0]++);
            world.addPluginChunkTicket(c[0], c[1], this); // keep it loaded until the round starts
            ticketedChunks.add(c);
            world.getChunkAt(c[0], c[1]);                 // loads / generates it
        }, 10L, 3L);
    }

    private void releasePreloadedChunks() {
        if (preloadTask != null) {
            preloadTask.cancel();
            preloadTask = null;
        }
        if (ticketedWorld != null) {
            for (int[] c : ticketedChunks) {
                ticketedWorld.removePluginChunkTicket(c[0], c[1], this);
            }
        }
        ticketedChunks.clear();
        ticketedWorld = null;
    }

    private void startNextRound() {
        breakTask = null;

        Player oldRunner = getRunner();
        List<Player> players = new ArrayList<>();
        if (oldRunner != null) {
            players.add(oldRunner);
        }
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                players.add(h);
            }
        }
        if (players.size() < 2) {
            Bukkit.broadcastMessage("§cNot enough players online to continue. Manhunt stopped.");
            resetAll();
            return;
        }

        // next runner: random, but never the same person twice in a row.
        // With only 2 players that simply means they take turns.
        List<Player> candidates = new ArrayList<>();
        for (Player p : players) {
            if (!p.getUniqueId().equals(runnerId)) {
                candidates.add(p);
            }
        }
        Player next = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        UUID previousId = runnerId;

        if (getConfig().getBoolean("reset-portals", true)) {
            resetPortals();
        }

        // clean slate for everybody: inventory, advancements, health, ...
        for (Player p : players) {
            freshStart(p);
        }

        // swap roles (the old runner becomes a hunter)
        becomeRunner(next);
        if (previousId != null && !previousId.equals(next.getUniqueId())) {
            Player previous = Bukkit.getPlayer(previousId);
            if (previous != null) {
                becomeHunter(previous);
            } else {
                hunters.add(previousId);
            }
        }

        List<Player> hs = new ArrayList<>();
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                hs.add(h);
            }
        }

        Bukkit.broadcastMessage("§6§lNEW ROUND §7- Speedrunner: §f" + next.getName());
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§6New speedrunner", "§f" + next.getName(), 5, 50, 10);
        }

        // same world/seed, but a random place
        World world = Bukkit.getWorlds().get(0);

        // every new round starts in the morning
        long morning = getConfig().getLong("round-start-time", 1000L);
        if (morning >= 0) {
            world.setTime(morning);
        }

        Location spot = nextSpot != null ? nextSpot : randomSafeCenter(world);
        nextSpot = null;
        gatherAndCountdown(next, hs, spot, true, freezeSeconds());
        releasePreloadedChunks(); // players are there now, so the chunks stay loaded anyway
        saveData();
    }

    private void clearInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
    }

    /** Fresh start for a new round: empty inventory, no advancements, full health, survival mode. */
    private void freshStart(Player p) {
        if (p.isDead()) {
            p.spigot().respawn();
        }
        if (getConfig().getBoolean("clear-inventory", true)) {
            clearInventory(p);
            p.getEnderChest().clear();
        }
        for (PotionEffect effect : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(effect.getType());
        }
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20.0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setExp(0f);
        p.setLevel(0);
        if (getConfig().getBoolean("reset-advancements", true)) {
            resetAdvancements(p);
        }
    }

    private void resetAdvancements(Player p) {
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (adv.getKey().getKey().startsWith("recipes/")) continue; // keep the recipe book
            AdvancementProgress progress = p.getAdvancementProgress(adv);
            for (String criteria : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criteria);
            }
        }
    }

    /** Random dry-land spot in the same world (can't change the seed, so we go somewhere new instead). */
    private Location randomSafeCenter(World world) {
        int radius = Math.max(100, getConfig().getInt("random-tp-radius", 3000));
        Location spawn = world.getSpawnLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            int x = spawn.getBlockX() + rnd.nextInt(-radius, radius + 1);
            int z = spawn.getBlockZ() + rnd.nextInt(-radius, radius + 1);
            Block top = world.getHighestBlockAt(x, z);
            if (isSafeGround(top)) {
                return new Location(world, x + 0.5, top.getY() + 1, z + 0.5);
            }
        }
        return groundAt(world, spawn.getX() + 0.5, spawn.getZ() + 0.5); // fallback: world spawn
    }

    private static boolean isSafeGround(Block b) {
        Material t = b.getType();
        if (b.isLiquid() || Tag.LEAVES.isTagged(t)) return false;
        if (t == Material.MAGMA_BLOCK || t == Material.CACTUS || t == Material.FIRE) return false;
        return t.isSolid() || t == Material.SNOW;
    }

    private static Location groundAt(World world, double x, double z) {
        int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
        return new Location(world, x, y, z);
    }

    private void teleportFrozen(Player p, Location loc) {
        p.leaveVehicle();
        p.setFallDistance(0f);
        p.teleport(loc);
        frozen.add(p.getUniqueId());
    }

    private void startCountdown(int seconds) {
        final int[] left = {seconds};
        countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (left[0] > 0) {
                int n = left[0];
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (n <= 5) {
                        p.sendTitle("§e" + n, "§7Get ready!", 0, 25, 5);
                        p.playSound(p.getLocation(), "block.note_block.hat", 1.0f, 1.0f);
                    } else if (frozen.contains(p.getUniqueId())) {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(
                                "§6Manhunt starts in §f" + n + " §6seconds - stay where you are!"));
                    }
                }
                left[0]--;
            } else {
                beginRound();
            }
        }, 0L, 20L);
    }

    /** Countdown finished: unfreeze everyone and start the round (win detection + timer). */
    private void beginRound() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        frozen.clear();

        if (runnerId == null) {
            Bukkit.broadcastMessage("§cManhunt cancelled - there is no speedrunner.");
            return;
        }

        roundActive = true;
        roundStart = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§a§lGO!", "§7The manhunt has started!", 0, 30, 10);
            p.playSound(p.getLocation(), "entity.player.levelup", 1.0f, 1.0f);
        }
        Bukkit.broadcastMessage("§6§lMANHUNT STARTED §7- Speedrunner: §f" + runnerName);
        saveData();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("manhunt")) {
            if (args.length == 1) {
                String start = args[0].toLowerCase();
                for (String sub : new String[]{"start", "random", "remove", "stats", "resetportals", "resetend", "version", "status", "reset", "compass"}) {
                    if (sub.startsWith(start)) out.add(sub);
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
                if ("reset".startsWith(args[1].toLowerCase())) out.add("reset");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                String start = args[1].toLowerCase();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(start)) out.add(p.getName());
                }
            }
            return out;
        }

        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
        }
        return out;
    }

    // ------------------------------------------------------------------ round handling

    private void resetAll() {
        if (breakTask != null) {
            breakTask.cancel();
            breakTask = null;
        }
        nextSpot = null;
        roundSpawn = null;
        releasePreloadedChunks();
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        frozen.clear();
        hunters.clear();
        runnerId = null;
        runnerName = "?";
        lastKnown.clear();
        roundActive = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeTrackers(p);
        }
        saveData();
    }

    private void notifyHunters(String message) {
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                h.sendMessage(message);
            }
        }
    }

    private static String formatElapsed(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long sec = total % 60L;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, sec);
        }
        return String.format(Locale.ROOT, "%dm %02ds", m, sec);
    }

    /** Announce the winner to everyone, then reset all roles + compasses. */
    private void endRound(boolean runnerWon, String detail) {
        if (!roundActive) return;
        roundActive = false;
        if (runnerWon) {
            runnerWins++;
        } else {
            hunterWins++;
        }

        String time = formatElapsed(System.currentTimeMillis() - roundStart);
        String title = runnerWon ? "§a§lSPEEDRUNNER WINS!" : "§c§lHUNTERS WIN!";
        String line = "§8§m------------------------------";

        Bukkit.broadcastMessage(line);
        Bukkit.broadcastMessage(title);
        Bukkit.broadcastMessage(detail);
        Bukkit.broadcastMessage("§7Round time: §f" + time);
        Bukkit.broadcastMessage("§7Score: §aRunner wins §f" + runnerWins + " §7| §cHunter wins §f" + hunterWins);
        Bukkit.broadcastMessage(line);

        String sound = runnerWon ? "ui.toast.challenge_complete" : "entity.ender_dragon.growl";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, detail, 10, 100, 20);
            p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }

        if (runnerWon) {
            // the dragon is dead: bring it (and the pillar crystals) back when someone enters the End again
            dragonRespawnPending = true;
        } else if (endVisited) {
            // the runner died after somebody was in the End: put back any crystals that got broken
            restoreEndCrystalsSoon();
        }
        endVisited = false;
        // short break, then a new round with a new runner
        saveData();
        scheduleNextRound();
    }

    // ------------------------------------------------------------------ portals

    private static String key(World w, int x, int y, int z) {
        return w.getUID() + ";" + x + ";" + y + ";" + z;
    }

    private static Block blockFromKey(String k) {
        String[] parts = k.split(";");
        if (parts.length != 4) return null;
        try {
            World w = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (w == null) return null;
            return w.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Removes the nether portals (+ their obsidian) made since the last reset and closes the stronghold
     * portal frames players put eyes in. Only portals made while this plugin was running are known.
     */
    private String resetPortals() {
        int netherBlocks = 0;
        for (String k : new ArrayList<>(netherPortalBlocks)) {
            Block b = blockFromKey(k);
            if (b == null) continue;
            Material t = b.getType();
            if (t == Material.NETHER_PORTAL) {
                for (BlockFace face : FACES) {
                    Block neighbor = b.getRelative(face);
                    if (neighbor.getType() == Material.OBSIDIAN) {
                        neighbor.setType(Material.AIR, false);
                    }
                }
                b.setType(Material.AIR, false);
                netherBlocks++;
            } else if (t == Material.OBSIDIAN) {
                b.setType(Material.AIR, false);
                netherBlocks++;
            }
        }
        netherPortalBlocks.clear();

        int frames = 0;
        for (String k : new ArrayList<>(eyeFrames)) {
            Block b = blockFromKey(k);
            if (b == null) continue;
            if (b.getBlockData() instanceof EndPortalFrame frame) {
                frame.setEye(false);
                b.setBlockData(frame, false);
                frames++;
            }
            // close the portal itself (the 3x3 of end portal blocks inside the frames)
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block pb = b.getRelative(dx, 0, dz);
                    if (pb.getType() == Material.END_PORTAL) {
                        pb.setType(Material.AIR, false);
                    }
                }
            }
        }
        eyeFrames.clear();

        saveData();
        return "Portals reset: " + netherBlocks + " nether portal block(s) removed, "
                + frames + " end portal frame(s) closed.";
    }

    private World findEnd() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.THE_END) {
                return w;
            }
        }
        return null;
    }

    /** Loads the pillar chunks, waits 2 seconds so existing crystals are known, then restores missing ones. */
    private void restoreEndCrystalsSoon() {
        final World end = findEnd();
        if (end == null) return;

        final List<int[]> chunks = new ArrayList<>();
        for (int j = 0; j < 10; j++) {
            int[] xz = pillarXZ(j);
            chunks.add(new int[]{xz[0] >> 4, xz[1] >> 4});
        }
        for (int[] c : chunks) {
            end.addPluginChunkTicket(c[0], c[1], this);
            end.getChunkAt(c[0], c[1]);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                int restored = restoreCrystals(end);
                if (restored > 0) {
                    Bukkit.broadcastMessage("§5" + restored + " broken end crystal(s) were put back on the pillars.");
                }
            } catch (Throwable t) {
                getLogger().warning("Could not restore end crystals: " + t);
            } finally {
                for (int[] c : chunks) {
                    end.removePluginChunkTicket(c[0], c[1], this);
                }
            }
        }, 40L);
    }

    /** Puts an end crystal on every pillar that has none (looks for the bedrock block on top of each pillar). */
    private int restoreCrystals(World end) {
        int restored = 0;
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int j = 0; j < 10; j++) {
            int[] xz = pillarXZ(j);
            Block base = null;
            search:
            for (int[] off : offsets) {
                for (int y = 125; y >= 60; y--) {
                    Block b = end.getBlockAt(xz[0] + off[0], y, xz[1] + off[1]);
                    if (b.getType() == Material.BEDROCK) {
                        base = b;
                        break search;
                    }
                }
            }
            if (base == null) continue;

            Location spot = new Location(end, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            boolean hasCrystal = false;
            for (Entity en : end.getNearbyEntities(spot, 2.0, 2.0, 2.0)) {
                if (en instanceof EnderCrystal && en.isValid()) {
                    hasCrystal = true;
                    break;
                }
            }
            if (hasCrystal) continue;

            Block above = base.getRelative(BlockFace.UP);
            if (above.getType() == Material.FIRE) {
                above.setType(Material.AIR, false);
            }
            EnderCrystal crystal = end.spawn(spot, EnderCrystal.class);
            crystal.setShowingBottom(false);
            restored++;
        }
        return restored;
    }

    /** Pillar spots of the End island (10 pillars on a circle of radius 42, same maths as the game). */
    private static int[] pillarXZ(int j) {
        double angle = 2.0 * (-Math.PI + (Math.PI / 10.0) * j);
        return new int[]{(int) Math.floor(42.0 * Math.cos(angle)), (int) Math.floor(42.0 * Math.sin(angle))};
    }

    private void say(CommandSender to, String message) {
        if (to != null) {
            to.sendMessage(message);
        }
    }

    /** Step-by-step info: to whoever ran the command (if any) and always to the console. */
    private void report(CommandSender to, String message) {
        getLogger().info("[End reset] " + message.replaceAll("§.", ""));
        say(to, "§7[End reset] §f" + message);
    }

    private int countDragons(World end) {
        int n = 0;
        for (EnderDragon d : end.getEntitiesByClass(EnderDragon.class)) {
            if (d.isValid()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Resets the dragon the real way: the dragon has to be dead, then 4 end crystals go on the
     * exit portal (one on each side) and the game runs its normal respawn: beams, pillars
     * exploding, new crystals on every pillar, new dragon.
     */
    private void resetEnd(World end, boolean manual, CommandSender feedback) {
        if (!dragonRespawnPending) {
            say(feedback, "§eNo End reset is pending.");
            return;
        }
        dragonRespawnPending = false;
        boolean byHand = manual || endResetManualRequested;
        endResetManualRequested = false;
        saveData();

        boolean dragonAlive = !end.getEntitiesByClass(EnderDragon.class).isEmpty();
        if (dragonAlive && !byHand) {
            report(feedback, "A dragon is already alive in the End, nothing to do.");
            return;
        }
        if (dragonAlive) {
            // like in a normal game: beat the dragon first, then respawn it with the 4 crystals
            killDragonThen(end, feedback, () -> tryRealRespawn(end, feedback, 5, true));
        } else {
            tryRealRespawn(end, feedback, byHand ? 5 : 2, byHand);
        }
    }

    /** Kills the living dragon (its normal death animation) and runs "then" once it is gone. */
    private void killDragonThen(World end, CommandSender feedback, Runnable then) {
        ignoreDragonDeath = true;
        for (EnderDragon d : end.getEntitiesByClass(EnderDragon.class)) {
            d.setHealth(0.0);
        }
        report(feedback, "Killing the current dragon first (takes about 10 seconds)...");

        final int[] seconds = {0};
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            seconds[0]++;
            if (end.getEntitiesByClass(EnderDragon.class).isEmpty() || seconds[0] >= 30) {
                holder[0].cancel();
                ignoreDragonDeath = false;
                then.run();
            }
        }, 20L, 20L);
    }

    /** Puts 4 crystals on the exit portal and starts the game's respawn. Retries a few times if the dragon is still dying. */
    private void tryRealRespawn(World end, CommandSender feedback, int attemptsLeft, boolean byHand) {
        List<EnderCrystal> crystals = new ArrayList<>();
        boolean started = false;
        try {
            DragonBattle battle = end.getEnderDragonBattle();
            Location portal = battle == null ? null : battle.getEndPortalLocation();
            if (battle == null) {
                report(feedback, "Dragon battle: not found");
            } else if (portal == null) {
                report(feedback, "Exit portal location: not known");
            } else {
                end.getChunkAt(portal);
                int[][] sides = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
                for (int[] side : sides) {
                    Location at = new Location(end,
                            portal.getBlockX() + side[0] + 0.5, portal.getBlockY() + 1.0, portal.getBlockZ() + side[1] + 0.5);
                    crystals.add(end.spawn(at, EnderCrystal.class));
                }
                started = battle.initiateRespawn(crystals);
            }
        } catch (Throwable t) {
            report(feedback, "Respawn attempt failed: " + t);
        }

        if (started) {
            report(feedback, "Respawn started: 4 crystals are on the exit portal. Wait about 30 seconds.");
            Bukkit.broadcastMessage("§5§lThe Ender Dragon is coming back!");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int n = countDragons(end);
                report(feedback, "Check after 45 seconds: " + n + " dragon(s) in the End.");
                if (n == 0 && byHand) {
                    report(feedback, "No dragon showed up, spawning one by hand.");
                    rebuildEndByHand(end, feedback);
                }
            }, 900L);
            return;
        }

        for (EnderCrystal c : crystals) {
            c.remove(); // the game did not accept them, take them away again
        }
        if (attemptsLeft > 0) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> tryRealRespawn(end, feedback, attemptsLeft - 1, byHand), 20L);
            return;
        }
        report(feedback, "The game would not start the respawn.");
        if (byHand) {
            rebuildEndByHand(end, feedback);
        } else {
            Bukkit.broadcastMessage("§5The Ender Dragon could not be respawned automatically. "
                    + "Place 4 end crystals on the exit portal, or an admin can use /manhunt resetend.");
        }
    }

    private void rebuildEndByHand(World end, CommandSender feedback) {
        try {
            end.getChunkAt(0, 0);

            int removedDragons = 0;
            for (EnderDragon d : end.getEntitiesByClass(EnderDragon.class)) {
                d.remove();
                removedDragons++;
            }
            int removedCrystals = 0;
            for (EnderCrystal c : end.getEntitiesByClass(EnderCrystal.class)) {
                Location l = c.getLocation();
                if (Math.abs(l.getX()) < 150 && Math.abs(l.getZ()) < 150) {
                    c.remove();
                    removedCrystals++;
                }
            }
            report(feedback, "Removed " + removedDragons + " dragon(s) and " + removedCrystals + " crystal(s).");

            int crystals = restoreCrystals(end);
            report(feedback, "Crystals placed on the pillars: " + crystals);

            EnderDragon dragon = end.spawn(new Location(end, 0.5, 128.0, 0.5), EnderDragon.class);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            report(feedback, "Dragon spawned: " + (dragon.isValid() ? "yes" : "no (entity is not valid)"));

            Bukkit.broadcastMessage("§5§lThe End was reset: " + crystals + " crystals and a fresh Ender Dragon are back!");
        } catch (Throwable t) {
            getLogger().warning("Could not reset the End: " + t);
            report(feedback, "§cBy-hand reset failed: " + t);
        }

        // check again in 3 seconds; if there is still no dragon, use the server's own summon command
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int n = countDragons(end);
            report(feedback, "Check: " + n + " dragon(s) in the End.");
            if (n == 0) {
                report(feedback, "Still no dragon, trying the server's summon command.");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "execute in minecraft:the_end run summon minecraft:ender_dragon 0 128 0");
            }
        }, 60L);
    }

    // ------------------------------------------------------------------ events

    /** Remember where the runner left a dimension (the portal they used). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!e.getPlayer().getUniqueId().equals(runnerId)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() == null || from.getWorld().equals(to.getWorld())) return;
        remember(from);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        frozen.remove(e.getPlayer().getUniqueId());
        if (e.getPlayer().getUniqueId().equals(runnerId)) {
            remember(e.getPlayer().getLocation());
            saveData();
            if (roundActive) {
                notifyHunters("§6" + runnerName + " §cdisconnected. §7Compass stays on their last known spot.");
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (hunters.contains(p.getUniqueId())) {
            giveCompass(p);
            refresh(p, getRunner(), false);
        }
        if (roundActive && p.getUniqueId().equals(runnerId)) {
            notifyHunters("§6" + runnerName + " §areconnected.");
        }
    }

    /** Hunters keep their compass: don't drop it on death, re-give on respawn. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (hunters.contains(dead.getUniqueId())) {
            e.getDrops().removeIf(this::isTracker);
        }
        if (roundActive && dead.getUniqueId().equals(runnerId)) {
            Player killer = dead.getKiller();
            String detail = killer != null
                    ? "§f" + dead.getName() + " §7was killed by §c" + killer.getName() + "§7!"
                    : "§f" + dead.getName() + " §7died!";
            // run next tick so we don't touch inventories in the middle of the death event
            Bukkit.getScheduler().runTask(this, () -> endRound(false, detail));
        }
    }

    /** Remember nether portals (lit by a player, or generated on the other side). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        PortalCreateEvent.CreateReason reason = e.getReason();
        if (reason != PortalCreateEvent.CreateReason.FIRE && reason != PortalCreateEvent.CreateReason.NETHER_PAIR) return;
        for (BlockState st : e.getBlocks()) {
            netherPortalBlocks.add(key(st.getWorld(), st.getX(), st.getY(), st.getZ()));
        }
        saveData();
    }

    /** Remember which stronghold portal frames got an eye of ender. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEyePlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.END_PORTAL_FRAME) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE) return;
        if (b.getBlockData() instanceof EndPortalFrame frame && !frame.hasEye()) {
            eyeFrames.add(key(b.getWorld(), b.getX(), b.getY(), b.getZ()));
            saveData();
        }
    }

    /** After a dragon win: the first player entering the End in the next round brings the dragon back. */
    @EventHandler
    public void onEnterEnd(PlayerChangedWorldEvent e) {
        if (roundActive && e.getPlayer().getWorld().getEnvironment() == World.Environment.THE_END) {
            endVisited = true;
        }
        if (!dragonRespawnPending) return;
        if (!roundActive && !endResetManualRequested) return;
        World world = e.getPlayer().getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return;
        if (endResetScheduled) return;
        endResetScheduled = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            endResetScheduled = false;
            resetEnd(world, false, null);
        }, 60L);
    }

    /** Frozen players can look around but not move. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.contains(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        e.setTo(fixed);
    }

    /** Nobody gets hurt while frozen at the start. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (frozen.isEmpty()) return;
        if (e.getEntity() instanceof Player p && frozen.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /** No breaking blocks during the start countdown; it works again once the round begins. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (frozen.isEmpty()) return;
        if (frozen.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cYou can't break blocks until the manhunt starts!");
        }
    }

    /** Ender Dragon dies while a round is running = speedrunner wins. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.ENDER_DRAGON) return;
        if (ignoreDragonDeath) return;
        if (!roundActive || runnerId == null) return;
        Bukkit.getScheduler().runTask(this, () ->
                endRound(true, "§f" + runnerName + " §7defeated the Ender Dragon!"));
    }

    /** Tell hunters when the runner changes dimension. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!roundActive || !p.getUniqueId().equals(runnerId)) return;
        notifyHunters("§6" + runnerName + " §7entered the §c" + dimension(p.getWorld()) + "§7.");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!hunters.contains(p.getUniqueId())) return;

        // respawn where this round started, not at the far-away world spawn
        if (roundSpawn != null && roundSpawn.getWorld() != null) {
            Location safe = standableNear(roundSpawn, roundSpawn.getX(), roundSpawn.getZ());
            e.setRespawnLocation(safe != null ? safe : roundSpawn.clone());
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            giveCompass(p);
            refresh(p, getRunner(), false);
        }, 1L);
    }

    /** Right-click = instant refresh + info. Also stops the compass being re-bound to a lodestone block. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isTracker(e.getItem())) return;

        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType() == Material.LODESTONE) {
            e.setCancelled(true);
        }

        Player runner = getRunner();
        if (runner != null) remember(runner.getLocation());
        refresh(e.getPlayer(), runner, true);
    }
}
