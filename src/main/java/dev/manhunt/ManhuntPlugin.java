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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /** true from /speedrunner until someone wins (or /manhunt reset) */
    private boolean roundActive = false;
    private long roundStart = 0L;

    /** world UID -> last known runner location in that world */
    private final Map<UUID, Location> lastKnown = new HashMap<>();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        hunters.add(target.getUniqueId());
        giveCompass(target);
        refresh(target, getRunner(), false);

        sender.sendMessage("§a" + target.getName() + " is now a hunter.");
        target.sendMessage("§cYou are now a HUNTER! §7Use your compass to track "
                + (runnerId == null ? "the speedrunner." : runnerName + "."));
        return true;
    }

    private boolean cmdSpeedrunner(CommandSender sender, String[] args) {
        if (args.length < 1) return false;
        Player target = findPlayer(sender, args[0]);
        if (target == null) return true;

        runnerId = target.getUniqueId();
        runnerName = target.getName();
        hunters.remove(runnerId);
        removeTrackers(target);

        lastKnown.clear();
        remember(target.getLocation());

        roundActive = true;
        roundStart = System.currentTimeMillis();
        Bukkit.broadcastMessage("§6§lMANHUNT STARTED §7- Speedrunner: §f" + runnerName);

        sender.sendMessage("§a" + target.getName() + " is now the speedrunner.");
        target.sendMessage("§aYou are the SPEEDRUNNER! §7Hunters are tracking you.");
        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null) {
                giveCompass(h);
                refresh(h, target, false);
                h.sendMessage("§6" + runnerName + " §7is now the speedrunner. Your compass tracks them.");
            }
        }
        return true;
    }

    private static final String MANHUNT_USAGE = "§cUsage: /manhunt <status|reset|compass>";

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
                return true;
            }
            case "reset": {
                resetAll();
                sender.sendMessage("§aManhunt reset. All tracker compasses removed.");
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("manhunt")) {
            if (args.length == 1) {
                String start = args[0].toLowerCase();
                for (String sub : new String[]{"status", "reset", "compass"}) {
                    if (sub.startsWith(start)) out.add(sub);
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
        hunters.clear();
        runnerId = null;
        runnerName = "?";
        lastKnown.clear();
        roundActive = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeTrackers(p);
        }
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
        if (e.getPlayer().getUniqueId().equals(runnerId)) {
            remember(e.getPlayer().getLocation());
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
