package dev.waystones;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Waystones: a 3-block pillar (diamond block base / stone brick wall / enchanting table top).
 * Craft the item, place it, right-click to open the teleport menu.
 */
public class WaystonePlugin extends JavaPlugin implements Listener, TabExecutor {

    // ---------- data ----------
    static class Waystone {
        final UUID id;
        String name;
        final UUID owner;
        final String ownerName;
        final Location base;

        Waystone(UUID id, String name, UUID owner, String ownerName, Location base) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.ownerName = ownerName;
            this.base = base;
        }
    }

    static class WaystoneMenu implements InventoryHolder {
        final UUID source;
        Inventory inv;

        WaystoneMenu(UUID source) { this.source = source; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, Waystone> waystones = new LinkedHashMap<>();
    private final Map<String, Waystone> blockIndex = new HashMap<>();
    private final Map<UUID, Set<UUID>> discovered = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    // player -> waystone they are currently naming (next chat message becomes the name)
    private final Map<UUID, UUID> pendingNames = new HashMap<>();
    private NamespacedKey itemKey, idKey, recipeKey;
    private File dataFile;
    private int counter = 0;

    // ---------- lifecycle ----------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "waystone_item");
        idKey = new NamespacedKey(this, "waystone_id");
        recipeKey = new NamespacedKey(this, "waystone");
        dataFile = new File(getDataFolder(), "data.yml");

        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("waystone");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // Load after all worlds are available
        getServer().getScheduler().runTask(this, this::load);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
        save();
    }

    // ---------- item & recipe ----------
    private ItemStack createItem() {
        ItemStack it = new ItemStack(Material.LODESTONE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Waystone");
        m.setLore(List.of(
                ChatColor.GRAY + "Place on the ground to build a waystone.",
                ChatColor.GRAY + "Right-click it to activate and travel."));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isWaystoneItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        ShapedRecipe r = new ShapedRecipe(recipeKey, createItem());
        r.shape("BOB", "SES", "DDD");
        r.setIngredient('B', Material.BOOK);
        r.setIngredient('O', Material.OBSIDIAN);
        r.setIngredient('S', Material.STONE_BRICKS);
        r.setIngredient('E', Material.ENDER_PEARL);
        r.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(r);
    }

    // ---------- helpers ----------
    private static String key(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    private void index(Waystone w) {
        waystones.put(w.id, w);
        for (int i = 0; i < 3; i++) {
            blockIndex.put(key(w.base.clone().add(0, i, 0)), w);
        }
    }

    private void unindex(Waystone w) {
        waystones.remove(w.id);
        for (int i = 0; i < 3; i++) {
            blockIndex.remove(key(w.base.clone().add(0, i, 0)));
        }
    }

    private Waystone at(Block b) {
        return blockIndex.get(key(b.getLocation()));
    }

    private String msg(String s) {
        return ChatColor.AQUA + "[Waystone] " + ChatColor.RESET + s;
    }

    // ---------- placing ----------
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isWaystoneItem(e.getItemInHand())) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("waystone.create")) {
            e.setCancelled(true);
            p.sendMessage(msg(ChatColor.RED + "You can't build waystones."));
            return;
        }
        Block base = e.getBlockPlaced();
        Block mid = base.getRelative(BlockFace.UP);
        Block top = mid.getRelative(BlockFace.UP);
        if (!mid.isEmpty() || !top.isEmpty() || top.getY() >= base.getWorld().getMaxHeight()) {
            e.setCancelled(true);
            p.sendMessage(msg(ChatColor.RED + "You need 3 blocks of free space going up."));
            return;
        }
        base.setType(Material.DIAMOND_BLOCK);
        mid.setType(Material.STONE_BRICK_WALL);
        top.setType(Material.ENCHANTING_TABLE);

        Waystone w = new Waystone(UUID.randomUUID(), "Waystone " + (++counter),
                p.getUniqueId(), p.getName(), base.getLocation());
        index(w);
        discovered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(w.id);
        save();

        base.getWorld().playSound(base.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        pendingNames.put(p.getUniqueId(), w.id);
        p.sendMessage(msg("Waystone created! " + ChatColor.YELLOW + "Type a name for it in chat"
                + ChatColor.RESET + " (or type " + ChatColor.GRAY + "skip" + ChatColor.RESET
                + " to keep \"" + w.name + "\")."));
        p.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("Name your waystone",
                        net.kyori.adventure.text.format.NamedTextColor.AQUA),
                net.kyori.adventure.text.Component.text("Type the name in chat",
                        net.kyori.adventure.text.format.NamedTextColor.GRAY)));
    }

    // ---------- naming via chat ----------
    @EventHandler
    public void onChatName(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!pendingNames.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        // Apply on the main thread
        getServer().getScheduler().runTask(this, () -> applyPendingName(p, text));
    }

    private void applyPendingName(Player p, String text) {
        UUID wid = pendingNames.get(p.getUniqueId());
        if (wid == null) return;
        Waystone w = waystones.get(wid);
        if (w == null) { // removed before it was named
            pendingNames.remove(p.getUniqueId());
            return;
        }
        if (text.equalsIgnoreCase("skip") || text.equalsIgnoreCase("cancel")) {
            pendingNames.remove(p.getUniqueId());
            p.sendMessage(msg("Keeping the name " + ChatColor.GREEN + w.name + ChatColor.RESET
                    + ". You can change it later with /waystone rename <name>."));
            return;
        }
        String name = ChatColor.translateAlternateColorCodes('&', text);
        if (ChatColor.stripColor(name).isBlank() || ChatColor.stripColor(name).length() > 32) {
            p.sendMessage(msg(ChatColor.RED + "Names must be 1-32 characters. Try again (or type skip)."));
            return;
        }
        pendingNames.remove(p.getUniqueId());
        w.name = name;
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        p.sendMessage(msg("Waystone named " + ChatColor.GREEN + name + ChatColor.RESET + "!"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pendingNames.remove(e.getPlayer().getUniqueId());
    }

    // ---------- breaking / protection ----------
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Waystone w = at(e.getBlock());
        if (w == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!p.getUniqueId().equals(w.owner) && !p.hasPermission("waystone.admin")) {
            p.sendMessage(msg(ChatColor.RED + "Only " + w.ownerName + " can remove this waystone."));
            return;
        }
        unindex(w);
        for (int i = 0; i < 3; i++) w.base.clone().add(0, i, 0).getBlock().setType(Material.AIR);
        if (p.getGameMode() != GameMode.CREATIVE) {
            w.base.getWorld().dropItemNaturally(w.base.clone().add(0.5, 0.5, 0.5), createItem());
        }
        save();
        p.sendMessage(msg("Waystone \"" + w.name + "\" removed."));
    }

    private boolean isWaystoneBlock(Block b) {
        return at(b) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isWaystoneBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isWaystoneBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }

    // ---------- using ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Waystone w = at(b);
        if (w == null) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.hasPermission("waystone.use")) {
            p.sendMessage(msg(ChatColor.RED + "You can't use waystones."));
            return;
        }
        Set<UUID> known = discovered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (known.add(w.id)) {
            save();
            p.sendMessage(msg("Discovered " + ChatColor.GREEN + w.name + ChatColor.RESET + "!"));
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        }
        openMenu(p, w);
    }

    private void openMenu(Player p, Waystone from) {
        boolean needDiscovery = getConfig().getBoolean("require-discovery", true);
        boolean cross = getConfig().getBoolean("cross-world", true);
        Set<UUID> known = discovered.getOrDefault(p.getUniqueId(), Collections.emptySet());

        List<Waystone> list = waystones.values().stream()
                .filter(w -> !w.id.equals(from.id))
                .filter(w -> !needDiscovery || known.contains(w.id))
                .filter(w -> cross || w.base.getWorld().equals(from.base.getWorld()))
                .sorted(Comparator.comparing(w -> w.name.toLowerCase()))
                .limit(54)
                .collect(Collectors.toList());

        int size = Math.max(9, (int) Math.ceil(list.size() / 9.0) * 9);
        WaystoneMenu holder = new WaystoneMenu(from.id);
        Inventory inv = Bukkit.createInventory(holder, size, "Waystone: " + from.name);
        holder.inv = inv;

        for (Waystone w : list) {
            ItemStack it = new ItemStack(Material.ENDER_PEARL);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(ChatColor.GREEN + w.name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Owner: " + w.ownerName);
            lore.add(ChatColor.GRAY + "World: " + w.base.getWorld().getName());
            if (w.base.getWorld().equals(p.getWorld())) {
                lore.add(ChatColor.GRAY + "Distance: " + (int) w.base.distance(p.getLocation()) + " blocks");
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to teleport");
            m.setLore(lore);
            m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, w.id.toString());
            it.setItemMeta(m);
            inv.addItem(it);
        }
        if (list.isEmpty()) {
            p.sendMessage(msg(ChatColor.GRAY + "No other waystones known yet. Go find some!"));
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof WaystoneMenu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (id == null) return;
        Player p = (Player) e.getWhoClicked();
        Waystone dest = waystones.get(UUID.fromString(id));
        p.closeInventory();
        if (dest == null) {
            p.sendMessage(msg(ChatColor.RED + "That waystone no longer exists."));
            return;
        }
        teleport(p, dest);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof WaystoneMenu) e.setCancelled(true);
    }

    // ---------- teleporting ----------
    private void teleport(Player p, Waystone dest) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("cooldown-seconds", 5) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("waystone.admin")) {
            long left = (cd - (now - last) + 999) / 1000;
            p.sendMessage(msg(ChatColor.RED + "Wait " + left + "s before teleporting again."));
            return;
        }
        Location target = safeSpot(dest, p);
        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);

        p.teleport(target);
        cooldowns.put(p.getUniqueId(), now);

        target.getWorld().spawnParticle(Particle.PORTAL, target.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
        target.getWorld().playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        p.sendMessage(msg("Teleported to " + ChatColor.GREEN + dest.name + ChatColor.RESET + "."));
    }

    private Location safeSpot(Waystone w, Player p) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : faces) {
            Block feet = w.base.getBlock().getRelative(f);
            Block head = feet.getRelative(BlockFace.UP);
            Block ground = feet.getRelative(BlockFace.DOWN);
            if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                    && ground.getType().isSolid()) {
                Location l = feet.getLocation().add(0.5, 0, 0.5);
                l.setYaw(p.getLocation().getYaw());
                l.setPitch(p.getLocation().getPitch());
                return l;
            }
        }
        // Fallback: stand on top of the waystone
        Location l = w.base.clone().add(0.5, 3, 0.5);
        l.setYaw(p.getLocation().getYaw());
        l.setPitch(p.getLocation().getPitch());
        return l;
    }

    // ---------- commands ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player)) {
            s.sendMessage("Players only.");
            return true;
        }
        Player p = (Player) s;
        if (a.length == 0) {
            p.sendMessage(msg("/waystone rename <name>  - rename the waystone you're looking at"));
            p.sendMessage(msg("/waystone list           - list waystones you've discovered"));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "rename": {
                if (a.length < 2) {
                    p.sendMessage(msg(ChatColor.RED + "Usage: /waystone rename <name>"));
                    return true;
                }
                Block target = p.getTargetBlockExact(6);
                Waystone w = target == null ? null : at(target);
                if (w == null) {
                    p.sendMessage(msg(ChatColor.RED + "Look at a waystone within 6 blocks."));
                    return true;
                }
                if (!p.getUniqueId().equals(w.owner) && !p.hasPermission("waystone.admin")) {
                    p.sendMessage(msg(ChatColor.RED + "Only the owner can rename this waystone."));
                    return true;
                }
                String name = ChatColor.translateAlternateColorCodes('&',
                        String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
                if (ChatColor.stripColor(name).length() > 32) {
                    p.sendMessage(msg(ChatColor.RED + "Name too long (max 32 characters)."));
                    return true;
                }
                w.name = name;
                save();
                p.sendMessage(msg("Renamed to " + ChatColor.GREEN + name + ChatColor.RESET + "."));
                return true;
            }
            case "list": {
                Set<UUID> known = discovered.getOrDefault(p.getUniqueId(), Collections.emptySet());
                List<Waystone> list = waystones.values().stream()
                        .filter(w -> known.contains(w.id)).collect(Collectors.toList());
                p.sendMessage(msg("You've discovered " + list.size() + " waystone(s):"));
                for (Waystone w : list) {
                    p.sendMessage(ChatColor.GRAY + " - " + ChatColor.GREEN + w.name + ChatColor.GRAY
                            + " (" + w.base.getWorld().getName() + " " + w.base.getBlockX() + ", "
                            + w.base.getBlockY() + ", " + w.base.getBlockZ() + ")");
                }
                return true;
            }
            default:
                p.sendMessage(msg(ChatColor.RED + "Unknown subcommand."));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("rename", "list");
        return Collections.emptyList();
    }

    // ---------- persistence ----------
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("counter", counter);
        for (Waystone w : waystones.values()) {
            String p = "waystones." + w.id;
            y.set(p + ".name", w.name);
            y.set(p + ".owner", w.owner.toString());
            y.set(p + ".ownerName", w.ownerName);
            y.set(p + ".world", w.base.getWorld().getName());
            y.set(p + ".x", w.base.getBlockX());
            y.set(p + ".y", w.base.getBlockY());
            y.set(p + ".z", w.base.getBlockZ());
        }
        for (Map.Entry<UUID, Set<UUID>> en : discovered.entrySet()) {
            y.set("discovered." + en.getKey(),
                    en.getValue().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save waystone data: " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        counter = y.getInt("counter", 0);
        ConfigurationSection ws = y.getConfigurationSection("waystones");
        if (ws != null) {
            for (String id : ws.getKeys(false)) {
                ConfigurationSection s = ws.getConfigurationSection(id);
                if (s == null) continue;
                World world = Bukkit.getWorld(s.getString("world", ""));
                if (world == null) {
                    getLogger().warning("Skipping waystone " + id + ": world not loaded.");
                    continue;
                }
                Location base = new Location(world, s.getInt("x"), s.getInt("y"), s.getInt("z"));
                index(new Waystone(UUID.fromString(id), s.getString("name", "Waystone"),
                        UUID.fromString(s.getString("owner")), s.getString("ownerName", "?"), base));
            }
        }
        ConfigurationSection d = y.getConfigurationSection("discovered");
        if (d != null) {
            for (String pid : d.getKeys(false)) {
                Set<UUID> set = new HashSet<>();
                for (String wid : d.getStringList(pid)) set.add(UUID.fromString(wid));
                discovered.put(UUID.fromString(pid), set);
            }
        }
        getLogger().info("Loaded " + waystones.size() + " waystone(s).");
    }
}
