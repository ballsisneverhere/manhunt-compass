package dev.manhunt;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private NamespacedKey trackerKey;

    private final Set<UUID> hunters = new HashSet<>();
    private UUID runnerId;
    private String runnerName = "?";

    /** true from the end of the /manhunt start countdown until someone wins (or /manhunt reset) */
    private boolean roundActive = false;
    private long roundStart = 0L;

    /** world UID -> last known runner location in that world */
    private final Map<UUID, Location> lastKnown = new HashMap<>();

    /** players who can't move during the start countdown */
    private final Set<UUID> frozen = new HashSet<>();
    private BukkitTask countdownTask;
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
        getLogger().info("ManhuntCompass enabled. Use /speedrunner <player> and /hunter <player>.");
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
        if (actionBar && holdingTracker(hunter)) {
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
            "§cUsage: /manhunt <start|random|remove <player>|status|reset|compass>";

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
                sender.sendMessage("§6Round: §f" + (roundActive ? "running" : "not started"));
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

        roundActive = false;

        // runner in the middle of the world spawn, hunters in a circle around them
        World world = Bukkit.getWorlds().get(0);
        Location center = world.getSpawnLocation().add(0.5, 0.0, 0.5);
        Location runnerSpot = groundAt(world, center.getX(), center.getZ());
        teleportFrozen(runner, runnerSpot);

        int n = hs.size();
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            Location spot = groundAt(world,
                    center.getX() + CIRCLE_RADIUS * Math.cos(angle),
                    center.getZ() + CIRCLE_RADIUS * Math.sin(angle));
            spot.setDirection(runnerSpot.toVector().subtract(spot.toVector())); // face the runner
            teleportFrozen(hs.get(i), spot);
        }

        lastKnown.clear();
        remember(runner.getLocation());

        Bukkit.broadcastMessage("§6§lMANHUNT §7starts in §f" + COUNTDOWN_SECONDS
                + " §7seconds. Everyone is frozen until then!");
        startCountdown();
        return true;
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

    private void startCountdown() {
        final int[] left = {COUNTDOWN_SECONDS};
        countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (left[0] > 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle("§e" + left[0], "§7Get ready!", 0, 25, 5);
                    p.playSound(p.getLocation(), "block.note_block.hat", 1.0f, 1.0f);
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
                for (String sub : new String[]{"start", "random", "remove", "status", "reset", "compass"}) {
                    if (sub.startsWith(start)) out.add(sub);
                }
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

        String time = formatElapsed(System.currentTimeMillis() - roundStart);
        String title = runnerWon ? "§a§lSPEEDRUNNER WINS!" : "§c§lHUNTERS WIN!";
        String line = "§8§m------------------------------";

        Bukkit.broadcastMessage(line);
        Bukkit.broadcastMessage(title);
        Bukkit.broadcastMessage(detail);
        Bukkit.broadcastMessage("§7Round time: §f" + time);
        Bukkit.broadcastMessage(line);

        String sound = runnerWon ? "ui.toast.challenge_complete" : "entity.ender_dragon.growl";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, detail, 10, 100, 20);
            p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }

        resetAll();
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

    /** Ender Dragon dies while a round is running = speedrunner wins. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.ENDER_DRAGON) return;
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
