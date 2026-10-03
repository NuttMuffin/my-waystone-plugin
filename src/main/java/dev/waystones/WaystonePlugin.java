package dev.waystones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Waystones: a 3-block pillar (diamond block / stone brick wall / enchanting table).
 * Fully GUI driven. Commands are optional shortcuts.
 */
public class WaystonePlugin extends JavaPlugin implements Listener, TabExecutor {

    // ====================================================================
    // Types
    // ====================================================================
    enum Kind { TELEPORT, VISIBILITY, SETTINGS, ICON, WHITELIST, HOLOGRAM, DISMANTLE, PERSONAL }
    enum Dim { OVERWORLD, NETHER, END }
    enum Sort { DISTANCE, NAME, NEWEST }

    static class Waystone {
        final UUID id;
        String name;
        final UUID owner;
        final String ownerName;
        final Location base;
        boolean isPrivate;
        boolean active;
        Material icon = Material.ENDER_PEARL;
        ChatColor nameColor = ChatColor.WHITE;
        Material holoItem;          // optional floating item
        boolean holoItemOn;
        final Set<UUID> whitelist = new HashSet<>();
        long created = System.currentTimeMillis();

        // live hologram entities (not saved)
        TextDisplay holoName, holoActive, holoStatus;
        ItemDisplay holoItemEnt;
        String textName, textActive, textStatus;
        Material shownItem;
        double spin;

        // Bedrock-friendly copies: invisible armor stands with name tags / a helmet item
        ArmorStand bName, bActive, bStatus, bItem;
        String bTextName, bTextActive, bTextStatus;
        Material bShownItem;

        Waystone(UUID id, String name, UUID owner, String ownerName, Location base) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.ownerName = ownerName;
            this.base = base;
        }
    }

    /** One holder class for every custom menu. */
    static class Gui implements InventoryHolder {
        final Kind kind;
        final UUID waystone;
        Dim dim = Dim.OVERWORLD;
        Sort sort = Sort.DISTANCE;
        int page = 0;
        boolean favOnly = false;
        boolean favMode = false;
        Inventory inv;

        Gui(Kind kind, UUID waystone) {
            this.kind = kind;
            this.waystone = waystone;
        }

        @Override
        public Inventory getInventory() { return inv; }
    }

    static class AnvilSession {
        final UUID waystone;
        final boolean isNew;

        AnvilSession(UUID waystone, boolean isNew) {
            this.waystone = waystone;
            this.isNew = isNew;
        }
    }

    static class Seat {
        final Entity parent, child;

        Seat(Entity parent, Entity child) {
            this.parent = parent;
            this.child = child;
        }
    }

    private record Hazard(String text, boolean danger) {}

    private static class Spot {
        Location loc;
        String reason;
    }

    // ====================================================================
    // Constants & state
    // ====================================================================
    private static final int PER_PAGE = 45;
    private static final int TP_PER_PAGE = 36; // teleport menu: 4 rows of waystones + 2 control rows

    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.COBWEB);

    private static final ChatColor[] PALETTE = {
            ChatColor.WHITE, ChatColor.YELLOW, ChatColor.GOLD, ChatColor.RED, ChatColor.DARK_RED,
            ChatColor.LIGHT_PURPLE, ChatColor.DARK_PURPLE, ChatColor.BLUE, ChatColor.AQUA,
            ChatColor.DARK_AQUA, ChatColor.GREEN, ChatColor.DARK_GREEN, ChatColor.GRAY, ChatColor.DARK_GRAY};
    private static final Material[] PALETTE_MATS = {
            Material.WHITE_CONCRETE, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE, Material.RED_CONCRETE,
            Material.RED_NETHER_BRICKS, Material.PINK_CONCRETE, Material.PURPLE_CONCRETE, Material.BLUE_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE, Material.CYAN_CONCRETE, Material.LIME_CONCRETE, Material.GREEN_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE};
    private static final int[] PALETTE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private static final Material[] ICON_PRESETS = {
            Material.ENDER_PEARL, Material.ENDER_EYE, Material.COMPASS, Material.CLOCK, Material.MAP,
            Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.NETHER_STAR, Material.BEACON,
            Material.LODESTONE, Material.TORCH, Material.LANTERN, Material.CAMPFIRE, Material.BELL,
            Material.RED_BED, Material.CHEST, Material.CRAFTING_TABLE, Material.GRASS_BLOCK,
            Material.OAK_SAPLING, Material.TOTEM_OF_UNDYING};
    private static final int[] ICON_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final Map<UUID, Waystone> waystones = new LinkedHashMap<>();
    private final Map<String, Waystone> blockIndex = new HashMap<>();
    private final Map<UUID, Set<UUID>> discovered = new HashMap<>();
    private final Map<UUID, Set<UUID>> favorites = new HashMap<>();
    private final Map<UUID, Map<UUID, Material>> personalIcons = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();
    private final Map<UUID, AnvilSession> anvilSessions = new HashMap<>();
    private NamespacedKey itemKey, packedKey, actionKey, recipeKey;
    private File dataFile;
    private int counter = 0;

    // ====================================================================
    // Lifecycle
    // ====================================================================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "waystone_item");
        packedKey = new NamespacedKey(this, "waystone_packed");
        actionKey = new NamespacedKey(this, "menu_action");
        recipeKey = new NamespacedKey(this, "waystone");
        dataFile = new File(getDataFolder(), "data.yml");

        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("unlock-recipe", true)) {
            for (Player online : Bukkit.getOnlinePlayers()) online.discoverRecipe(recipeKey);
        }
        PluginCommand cmd = getCommand("waystone");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getScheduler().runTask(this, this::load);
        getServer().getScheduler().runTaskTimer(this, this::tickHolograms, 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::spinBedrock, 40L, 3L);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
        // close anvil screens without triggering follow-up menus
        List<UUID> open = new ArrayList<>(anvilSessions.keySet());
        anvilSessions.clear();
        for (UUID id : open) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.getOpenInventory().getTopInventory().clear();
                p.closeInventory();
            }
        }
        for (BukkitTask t : warmups.values()) t.cancel();
        warmups.clear();
        for (Waystone w : waystones.values()) removeHolos(w);
        save();
    }

    // ====================================================================
    // Items & recipe
    // ====================================================================
    private ItemStack createItem() {
        ItemStack it = new ItemStack(Material.BREWING_STAND);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Waystone");
        m.setLore(List.of(
                ChatColor.GRAY + "Place on the ground to build a waystone.",
                ChatColor.GRAY + "Name it, pick Public or Private,",
                ChatColor.GRAY + "then click it to activate."));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack createPackedItem(Waystone w) {
        ItemStack it = new ItemStack(Material.BREWING_STAND);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Waystone: " + ChatColor.WHITE + plainName(w));
        m.setLore(List.of(
                ChatColor.GRAY + "A packed-up waystone.",
                ChatColor.GRAY + "Place it to move it. Settings are kept."));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", w.id.toString());
        writeFields(y, w);
        m.getPersistentDataContainer().set(packedKey, PersistentDataType.STRING, y.saveToString());
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
        r.setIngredient('E', Material.ENDER_EYE);
        r.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(r);
    }

    // ====================================================================
    // Helpers
    // ====================================================================
    private static String key(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    private void index(Waystone w) {
        waystones.put(w.id, w);
        for (int i = 0; i < 3; i++) blockIndex.put(key(w.base.clone().add(0, i, 0)), w);
    }

    private void unindex(Waystone w) {
        removeHolos(w);
        waystones.remove(w.id);
        for (int i = 0; i < 3; i++) blockIndex.remove(key(w.base.clone().add(0, i, 0)));
    }

    private Waystone at(Block b) {
        return blockIndex.get(key(b.getLocation()));
    }

    private String msg(String s) {
        return ChatColor.AQUA + "[Waystone] " + ChatColor.RESET + s;
    }

    private String plainName(Waystone w) {
        return ChatColor.stripColor(w.name);
    }

    private static String sanitizeName(String text) {
        return text.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "").trim();
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    private void later(Runnable r) {
        getServer().getScheduler().runTask(this, r);
    }

    private void sound(Player p, Sound s, float pitch) {
        p.playSound(p.getLocation(), s, 1f, pitch);
    }

    private boolean visibleTo(Player p, Waystone w) {
        return !w.isPrivate || w.owner.equals(p.getUniqueId()) || w.whitelist.contains(p.getUniqueId());
    }

    private boolean canUseBlock(Player p, Waystone w) {
        return visibleTo(p, w) || p.hasPermission("waystone.admin");
    }

    /** Settings are owner-only. */
    private boolean canManage(Player p, Waystone w) {
        return w.owner.equals(p.getUniqueId());
    }

    /** Breaking / first activation: the owner or an operator. */
    private boolean canRemove(Player p, Waystone w) {
        return w.owner.equals(p.getUniqueId()) || p.hasPermission("waystone.admin");
    }

    private Material iconFor(Player p, Waystone w) {
        Map<UUID, Material> mine = personalIcons.get(p.getUniqueId());
        if (mine != null) {
            Material m = mine.get(w.id);
            if (m != null) return m;
        }
        return w.icon;
    }

    private boolean isFavorite(Player p, Waystone w) {
        return favorites.getOrDefault(p.getUniqueId(), Collections.emptySet()).contains(w.id);
    }

    private boolean toggleFavorite(Player p, Waystone w) {
        Set<UUID> set = favorites.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        boolean now = set.add(w.id);
        if (!now) set.remove(w.id);
        save();
        return now;
    }

    private Waystone lookedAt(Player p) {
        Block target = p.getTargetBlockExact(6);
        return target == null ? null : at(target);
    }

    private static Dim dimOf(Waystone w) {
        World.Environment env = w.base.getWorld().getEnvironment();
        if (env == World.Environment.NETHER) return Dim.NETHER;
        if (env == World.Environment.THE_END) return Dim.END;
        return Dim.OVERWORLD;
    }

    private static String dimName(Dim d) {
        return switch (d) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "The End";
        };
    }

    // ====================================================================
    // Saving / loading fields (shared by data.yml and packed items)
    // ====================================================================
    private void writeFields(ConfigurationSection s, Waystone w) {
        s.set("name", w.name);
        s.set("owner", w.owner.toString());
        s.set("ownerName", w.ownerName);
        s.set("private", w.isPrivate);
        s.set("active", w.active);
        s.set("icon", w.icon.name());
        s.set("color", w.nameColor.name());
        s.set("holoItem", w.holoItem == null ? null : w.holoItem.name());
        s.set("holoItemOn", w.holoItemOn);
        s.set("whitelist", w.whitelist.stream().map(UUID::toString).collect(Collectors.toList()));
        s.set("created", w.created);
    }

    private void readFields(ConfigurationSection s, Waystone w) {
        w.isPrivate = s.getBoolean("private", false);
        w.active = s.getBoolean("active", true);
        Material icon = Material.matchMaterial(s.getString("icon", "ENDER_PEARL"));
        w.icon = icon == null ? Material.ENDER_PEARL : icon;
        try {
            w.nameColor = ChatColor.valueOf(s.getString("color", "WHITE"));
        } catch (IllegalArgumentException ex) {
            w.nameColor = ChatColor.WHITE;
        }
        String hi = s.getString("holoItem");
        w.holoItem = hi == null ? null : Material.matchMaterial(hi);
        w.holoItemOn = w.holoItem != null && s.getBoolean("holoItemOn", true);
        w.whitelist.clear();
        for (String id : s.getStringList("whitelist")) {
            try {
                w.whitelist.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) { }
        }
        w.created = s.getLong("created", System.currentTimeMillis());
    }

    private Waystone fromPacked(String data, Location loc) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException ex) {
            return null;
        }
        try {
            UUID id = UUID.fromString(y.getString("id", ""));
            if (waystones.containsKey(id)) id = UUID.randomUUID();
            Waystone w = new Waystone(id, y.getString("name", "Waystone"),
                    UUID.fromString(y.getString("owner", "")), y.getString("ownerName", "?"), loc);
            readFields(y, w);
            w.active = false; // must be activated again after moving
            return w;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ====================================================================
    // Placing
    // ====================================================================
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        if (!isWaystoneItem(inHand)) return;
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

        String packed = inHand.getItemMeta().getPersistentDataContainer().get(packedKey, PersistentDataType.STRING);
        Waystone restored = packed == null ? null : fromPacked(packed, base.getLocation());
        final Waystone w = restored != null ? restored
                : new Waystone(UUID.randomUUID(), "Waystone " + (++counter),
                p.getUniqueId(), p.getName(), base.getLocation());
        index(w);
        save();
        updateHolo(w);
        base.getWorld().playSound(base.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.8f);

        if (restored != null) {
            p.sendMessage(msg("Waystone moved! " + ChatColor.YELLOW + "Click it to activate it again."));
        } else {
            p.sendMessage(msg("Waystone placed! Name it, choose Public or Private, then "
                    + ChatColor.YELLOW + "click it to activate it" + ChatColor.RESET + "."));
            later(() -> openAnvil(p, w, true));
        }
    }

    // ====================================================================
    // Activation
    // ====================================================================
    private void activate(Player p, Waystone w) {
        if (w.active) return;
        w.active = true;
        discovered.computeIfAbsent(w.owner, k -> new HashSet<>()).add(w.id);
        discovered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(w.id);
        save();
        updateHolo(w);
        showActivated(p, w);
    }

    /** "Waystone Activated" feedback. The menu does NOT open on this first click. */
    private void showActivated(Player p, Waystone w) {
        World world = w.base.getWorld();
        Location c = w.base.clone().add(0.5, 1.5, 0.5);
        world.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.3f);
        world.playSound(c, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        world.spawnParticle(Particle.END_ROD, c, 60, 0.4, 1.0, 0.4, 0.05);
        p.showTitle(Title.title(
                Component.text("Waystone Activated", NamedTextColor.GREEN),
                Component.text(plainName(w), NamedTextColor.GRAY)));
        p.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET + " is now active. "
                + ChatColor.YELLOW + "Click it again to open the menu."));
    }

    // ====================================================================
    // Hologram (floating name, status line and optional spinning item)
    // ====================================================================
    private boolean isBedrock(Player p) {
        // Floodgate gives Bedrock players UUIDs that start with zeros
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private boolean anyBedrockOnline() {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (isBedrock(pl)) return true;
        }
        return false;
    }

    private float itemScale() {
        return (float) getConfig().getDouble("hologram.item-scale", 1.6);
    }

    private TextDisplay spawnText(Location loc) {
        return loc.getWorld().spawn(loc, TextDisplay.class, td -> {
            td.setVisibleByDefault(false); // shown per player below
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setShadowed(true);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        });
    }

    private void setText(TextDisplay td, String text) {
        td.text(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    private ArmorStand spawnStand(Location loc, boolean showName) {
        return loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setVisibleByDefault(false);
            as.setPersistent(false);
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setBasePlate(false);
            as.setCollidable(false);
            as.setCustomNameVisible(showName);
        });
    }

    private void setStandName(ArmorStand as, String text) {
        as.customName(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    private Transformation spinTransform(double angle) {
        float sc = itemScale();
        return new Transformation(new Vector3f(), new AxisAngle4f((float) angle, 0f, 1f, 0f),
                new Vector3f(sc, sc, sc), new AxisAngle4f(0f, 0f, 1f, 0f));
    }

    private void refreshHologram(Waystone w) {
        World world = w.base.getWorld();
        if (world == null || !world.isChunkLoaded(w.base.getBlockX() >> 4, w.base.getBlockZ() >> 4)) return;

        double nameY = getConfig().getDouble("hologram.name-y", 3.55);
        double statusY = getConfig().getDouble("hologram.status-y", 3.30);
        double itemY = getConfig().getDouble("hologram.item-y", 4.2);

        String name = w.nameColor + "" + ChatColor.ITALIC + plainName(w);
        String activeText = ChatColor.GREEN + "" + ChatColor.ITALIC + "Active";
        String statusText = w.isPrivate
                ? ChatColor.RED + "" + ChatColor.ITALIC + "Private"
                : ChatColor.GRAY + "" + ChatColor.ITALIC + "Inactive";
        boolean wantItem = w.holoItem != null && w.holoItemOn;

        // ---------- Java: text + item displays ----------
        if (w.holoName == null || !w.holoName.isValid()) {
            w.holoName = spawnText(w.base.clone().add(0.5, nameY, 0.5));
            w.textName = null;
        }
        if (w.holoActive == null || !w.holoActive.isValid()) {
            w.holoActive = spawnText(w.base.clone().add(0.5, statusY, 0.5));
            w.textActive = null;
        }
        if (w.holoStatus == null || !w.holoStatus.isValid()) {
            w.holoStatus = spawnText(w.base.clone().add(0.5, statusY, 0.5));
            w.textStatus = null;
        }
        if (!name.equals(w.textName)) {
            setText(w.holoName, name);
            w.textName = name;
        }
        if (!activeText.equals(w.textActive)) {
            setText(w.holoActive, activeText);
            w.textActive = activeText;
        }
        if (!statusText.equals(w.textStatus)) {
            setText(w.holoStatus, statusText);
            w.textStatus = statusText;
        }

        if (!wantItem) {
            if (w.holoItemEnt != null) {
                w.holoItemEnt.remove();
                w.holoItemEnt = null;
                w.shownItem = null;
            }
        } else if (w.holoItemEnt == null || !w.holoItemEnt.isValid()) {
            final Material mat = w.holoItem;
            final double spin = w.spin;
            w.holoItemEnt = world.spawn(w.base.clone().add(0.5, itemY, 0.5), ItemDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.setItemStack(new ItemStack(mat));
                d.setBillboard(Display.Billboard.FIXED);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(spinTransform(spin));
            });
            w.shownItem = w.holoItem;
        } else if (w.shownItem != w.holoItem) {
            w.holoItemEnt.setItemStack(new ItemStack(w.holoItem));
            w.shownItem = w.holoItem;
        }

        // ---------- Bedrock: armor stands with name tags (only while a Bedrock player is online) ----------
        boolean wantBedrock = getConfig().getBoolean("bedrock-holograms", true) && anyBedrockOnline();
        if (!wantBedrock) {
            removeBedrock(w);
            return;
        }
        if (w.bName == null || !w.bName.isValid()) {
            w.bName = spawnStand(w.base.clone().add(0.5, nameY - 0.5, 0.5), true);
            w.bTextName = null;
        }
        if (w.bActive == null || !w.bActive.isValid()) {
            w.bActive = spawnStand(w.base.clone().add(0.5, statusY - 0.5, 0.5), true);
            w.bTextActive = null;
        }
        if (w.bStatus == null || !w.bStatus.isValid()) {
            w.bStatus = spawnStand(w.base.clone().add(0.5, statusY - 0.5, 0.5), true);
            w.bTextStatus = null;
        }
        if (!name.equals(w.bTextName)) {
            setStandName(w.bName, name);
            w.bTextName = name;
        }
        if (!activeText.equals(w.bTextActive)) {
            setStandName(w.bActive, activeText);
            w.bTextActive = activeText;
        }
        if (!statusText.equals(w.bTextStatus)) {
            setStandName(w.bStatus, statusText);
            w.bTextStatus = statusText;
        }
        if (!wantItem) {
            if (w.bItem != null) {
                w.bItem.remove();
                w.bItem = null;
                w.bShownItem = null;
            }
        } else {
            if (w.bItem == null || !w.bItem.isValid()) {
                w.bItem = spawnStand(w.base.clone().add(0.5, itemY - 1.7, 0.5), false);
                w.bShownItem = null;
            }
            if (w.bShownItem != w.holoItem) {
                w.bItem.getEquipment().setHelmet(new ItemStack(w.holoItem));
                w.bShownItem = w.holoItem;
            }
        }
    }

    private void removeBedrock(Waystone w) {
        if (w.bName != null) w.bName.remove();
        if (w.bActive != null) w.bActive.remove();
        if (w.bStatus != null) w.bStatus.remove();
        if (w.bItem != null) w.bItem.remove();
        w.bName = null;
        w.bActive = null;
        w.bStatus = null;
        w.bItem = null;
        w.bTextName = null;
        w.bTextActive = null;
        w.bTextStatus = null;
        w.bShownItem = null;
    }

    /** Green "Active" only for players who discovered a public, activated waystone. */
    private boolean activeFor(Player p, Waystone w) {
        return w.active && !w.isPrivate
                && discovered.getOrDefault(p.getUniqueId(), Collections.emptySet()).contains(w.id);
    }

    private void setSeen(Player p, Entity e, boolean seen) {
        if (e == null || !e.isValid()) return;
        if (seen) p.showEntity(this, e);
        else p.hideEntity(this, e);
    }

    /** Java players see text/item displays, Bedrock players see the armor-stand versions. */
    private void applyVisibility(Player p, Waystone w) {
        if (!p.getWorld().equals(w.base.getWorld())) return;
        boolean bedrock = isBedrock(p);
        boolean active = activeFor(p, w);
        setSeen(p, w.holoName, !bedrock);
        setSeen(p, w.holoActive, !bedrock && active);
        setSeen(p, w.holoStatus, !bedrock && !active);
        setSeen(p, w.holoItemEnt, !bedrock);
        setSeen(p, w.bName, bedrock);
        setSeen(p, w.bActive, bedrock && active);
        setSeen(p, w.bStatus, bedrock && !active);
        setSeen(p, w.bItem, bedrock);
    }

    private void updateHolo(Waystone w) {
        refreshHologram(w);
        for (Player pl : Bukkit.getOnlinePlayers()) applyVisibility(pl, w);
    }

    private void tickHolograms() {
        for (Waystone w : waystones.values()) {
            updateHolo(w);
            if (w.holoItemEnt != null && w.holoItemEnt.isValid()) {
                w.spin += Math.PI / 3;
                if (w.spin > 600) w.spin = 0;
                w.holoItemEnt.setInterpolationDelay(0);
                w.holoItemEnt.setInterpolationDuration(20);
                w.holoItemEnt.setTransformation(spinTransform(w.spin));
            }
        }
    }

    private void spinBedrock() {
        for (Waystone w : waystones.values()) {
            if (w.bItem != null && w.bItem.isValid()) {
                w.bItem.setRotation(w.bItem.getLocation().getYaw() + 12f, 0f);
            }
        }
    }

    private void removeHolos(Waystone w) {
        if (w.holoName != null) w.holoName.remove();
        if (w.holoActive != null) w.holoActive.remove();
        if (w.holoStatus != null) w.holoStatus.remove();
        if (w.holoItemEnt != null) w.holoItemEnt.remove();
        w.holoName = null;
        w.holoActive = null;
        w.holoStatus = null;
        w.holoItemEnt = null;
        w.textName = null;
        w.textActive = null;
        w.textStatus = null;
        w.shownItem = null;
        removeBedrock(w);
    }

    // ====================================================================
    // Breaking / protection
    // ====================================================================
    private void deleteRecord(Waystone w) {
        unindex(w);
        for (int i = 0; i < 3; i++) w.base.clone().add(0, i, 0).getBlock().setType(Material.AIR);
        for (Set<UUID> set : favorites.values()) set.remove(w.id);
        for (Set<UUID> set : discovered.values()) set.remove(w.id);
        for (Map<UUID, Material> icons : personalIcons.values()) icons.remove(w.id);
        save();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Waystone w = at(e.getBlock());
        if (w == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!canRemove(p, w)) {
            p.sendMessage(msg(ChatColor.RED + "Only " + w.ownerName + " can remove this waystone."));
            return;
        }
        Location drop = w.base.clone().add(0.5, 0.5, 0.5);
        deleteRecord(w);
        if (p.getGameMode() != GameMode.CREATIVE) {
            drop.getWorld().dropItemNaturally(drop, createItem());
        }
        p.sendMessage(msg("Waystone \"" + plainName(w) + "\" removed."));
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
    public void onMobChangeBlock(EntityChangeBlockEvent e) {
        if (isWaystoneBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isWaystoneBlock)) e.setCancelled(true);
    }

    // ====================================================================
    // Clicking the waystone
    // ====================================================================
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
        boolean owner = canManage(p, w);
        if (p.isSneaking()) {
            if (owner) {
                openGui(p, Kind.SETTINGS, w);
            } else if (w.active && visibleTo(p, w)
                    && discovered.getOrDefault(p.getUniqueId(), Collections.emptySet()).contains(w.id)) {
                openGui(p, Kind.PERSONAL, w);
            } else {
                p.sendMessage(msg(ChatColor.RED + "Only the owner can change this waystone's settings."));
            }
            return;
        }
        if (!canUseBlock(p, w)) {
            p.sendMessage(msg(ChatColor.RED + "This waystone is private. It belongs to " + w.ownerName + "."));
            sound(p, Sound.BLOCK_CHEST_LOCKED, 1f);
            return;
        }
        if (!w.active) {
            if (canRemove(p, w)) activate(p, w);
            else p.sendMessage(msg(ChatColor.RED + "This waystone hasn't been activated yet."));
            return;
        }
        Set<UUID> known = discovered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (known.add(w.id)) {
            save();
            applyVisibility(p, w);
            showActivated(p, w);
            return; // the first click only activates it; click again to open the menu
        }
        openGui(p, Kind.TELEPORT, w);
    }

    // ====================================================================
    // Anvil naming
    // ====================================================================
    private ItemStack namePaper(String name, boolean input) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (input) m.setLore(List.of(ChatColor.GRAY + "Type a name above, then click",
                ChatColor.GRAY + "the result on the right."));
        m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "none");
        it.setItemMeta(m);
        return it;
    }

    private void openAnvil(Player p, Waystone w, boolean isNew) {
        if (!p.isOnline()) return;
        AnvilView view = MenuType.ANVIL.builder()
                .title(Component.text(isNew ? "Name your waystone" : "Rename waystone"))
                .checkReachable(false)
                .build(p);
        anvilSessions.put(p.getUniqueId(), new AnvilSession(w.id, isNew));
        p.openInventory(view);
        Inventory top = view.getTopInventory();
        top.setItem(0, namePaper(plainName(w), true));
        later(() -> {
            if (anvilSessions.containsKey(p.getUniqueId())) top.setItem(2, namePaper(plainName(w), false));
        });
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        AnvilSession s = anvilSessions.get(e.getView().getPlayer().getUniqueId());
        if (s == null) return;
        AnvilView view = e.getView();
        Waystone w = waystones.get(s.waystone);
        String text = view.getRenameText();
        if (text == null || text.isBlank()) text = w == null ? "Waystone" : plainName(w);
        e.setResult(namePaper(text, false));
        view.setRepairCost(0);
    }

    @EventHandler
    public void onAnvilClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        AnvilSession s = anvilSessions.get(p.getUniqueId());
        if (s == null || e.getView().getTopInventory().getType() != InventoryType.ANVIL) return;
        e.setCancelled(true);
        if (e.getRawSlot() != 2) return;

        Waystone w = waystones.get(s.waystone);
        if (w == null) {
            anvilSessions.remove(p.getUniqueId());
            e.getView().getTopInventory().clear();
            later(p::closeInventory);
            return;
        }
        String typed = null;
        if (e.getView() instanceof AnvilView av) typed = av.getRenameText();
        String name = sanitizeName(typed == null ? "" : typed);
        if (name.isEmpty()) name = plainName(w);
        if (name.length() > 32) {
            p.sendMessage(msg(ChatColor.RED + "Names can be at most 32 characters."));
            return;
        }
        w.name = name;
        save();
        updateHolo(w);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.5f);
        p.sendMessage(msg("Waystone named " + ChatColor.GREEN + name + ChatColor.RESET + "!"));

        anvilSessions.remove(p.getUniqueId());
        e.getView().getTopInventory().clear();
        final boolean isNew = s.isNew;
        later(() -> {
            if (!p.isOnline()) return;
            openGui(p, isNew ? Kind.VISIBILITY : Kind.SETTINGS, w);
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        AnvilSession s = anvilSessions.get(p.getUniqueId());
        if (s != null && e.getInventory().getType() == InventoryType.ANVIL) {
            anvilSessions.remove(p.getUniqueId());
            e.getInventory().clear(); // never hand the placeholder paper back
            Waystone w = waystones.get(s.waystone);
            if (s.isNew && w != null) {
                // closed without naming: keep the default name and move on
                later(() -> {
                    if (p.isOnline()) openGui(p, Kind.VISIBILITY, w);
                });
            }
            return;
        }
        if (e.getInventory().getHolder() instanceof Gui g && g.kind == Kind.VISIBILITY) {
            Waystone w = waystones.get(g.waystone);
            if (w != null && !w.active && canManage(p, w)) {
                p.sendMessage(msg(ChatColor.YELLOW + "Now click the waystone to activate it."));
                p.sendActionBar(Component.text("Click the waystone to activate it", NamedTextColor.YELLOW));
            }
        }
    }

    /** Adds the Waystone recipe to every player's recipe book when they join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (getConfig().getBoolean("unlock-recipe", true)) e.getPlayer().discoverRecipe(recipeKey);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        cancelWarmup(p, null);
        if (anvilSessions.remove(p.getUniqueId()) != null) {
            p.getOpenInventory().getTopInventory().clear();
        }
    }

    // ====================================================================
    // Menus
    // ====================================================================
    private ItemStack button(Material mat, String name, String action, boolean glow, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore.length > 0) m.setLore(Arrays.asList(lore));
        if (glow) m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(m);
        return it;
    }

    private void fill(Inventory inv, int from, int to) {
        ItemStack filler = button(Material.BLACK_STAINED_GLASS_PANE, " ", "none", false);
        for (int i = from; i < to; i++) inv.setItem(i, filler);
    }

    private void openGui(Player p, Kind kind, Waystone w) {
        Gui g = new Gui(kind, w.id);
        g.dim = dimOf(w);
        int size = switch (kind) {
            case TELEPORT, WHITELIST -> 54;
            case ICON, HOLOGRAM -> 45;
            case SETTINGS -> 36;
            default -> 27;
        };
        String shortName = plainName(w);
        if (shortName.length() > 18) shortName = shortName.substring(0, 18);
        String title = switch (kind) {
            case TELEPORT -> "Waystone: " + shortName;
            case VISIBILITY -> "Public or Private?";
            case SETTINGS -> "Settings: " + shortName;
            case ICON -> "Choose an icon";
            case WHITELIST -> "Whitelist";
            case HOLOGRAM -> "Hologram settings";
            case DISMANTLE -> "Dismantle waystone";
            case PERSONAL -> "My icon: " + shortName;
        };
        g.inv = Bukkit.createInventory(g, size, title);
        render(p, g);
        p.openInventory(g.inv);
    }

    private void render(Player p, Gui g) {
        Waystone w = waystones.get(g.waystone);
        if (w == null) {
            p.closeInventory();
            return;
        }
        g.inv.clear();
        switch (g.kind) {
            case TELEPORT -> renderTeleport(p, g, w);
            case VISIBILITY -> renderVisibility(g, w);
            case SETTINGS -> renderSettings(g, w);
            case ICON -> renderIcons(g, w);
            case WHITELIST -> renderWhitelist(g, w);
            case HOLOGRAM -> renderHologram(g, w);
            case DISMANTLE -> renderDismantle(g, w);
            case PERSONAL -> renderPersonal(p, g, w);
        }
    }

    // ---------- teleport menu ----------
    private double sortDistance(Player p, Waystone w) {
        Location pl = p.getLocation();
        if (pl.getWorld().equals(w.base.getWorld())) return pl.distance(w.base);
        double ps = pl.getWorld().getEnvironment() == World.Environment.NETHER ? 8.0 : 1.0;
        double ws = w.base.getWorld().getEnvironment() == World.Environment.NETHER ? 8.0 : 1.0;
        double dx = pl.getX() * ps - w.base.getX() * ws;
        double dz = pl.getZ() * ps - w.base.getZ() * ws;
        return Math.sqrt(dx * dx + dz * dz) + 1_000_000.0;
    }

    private ItemStack exitButton() {
        return button(Material.BARRIER, ChatColor.RED + "Exit", "close", false,
                ChatColor.GRAY + "Close this menu");
    }

    private List<Waystone> listFor(Player p, Waystone from, Dim dim, boolean favOnly, Sort sort) {
        boolean needDiscovery = getConfig().getBoolean("require-discovery", true);
        boolean cross = getConfig().getBoolean("cross-world", true);
        Set<UUID> known = discovered.getOrDefault(p.getUniqueId(), Collections.emptySet());
        Set<UUID> favs = favorites.getOrDefault(p.getUniqueId(), Collections.emptySet());

        Comparator<Waystone> pinned = Comparator.comparing((Waystone w) -> !favs.contains(w.id));
        Comparator<Waystone> second = switch (sort) {
            case DISTANCE -> Comparator.comparingDouble((Waystone w) -> sortDistance(p, w));
            case NAME -> Comparator.comparing((Waystone w) -> plainName(w).toLowerCase());
            case NEWEST -> Comparator.comparingLong((Waystone w) -> -w.created);
        };
        Comparator<Waystone> order = favOnly ? second : pinned.thenComparing(second);

        return waystones.values().stream()
                .filter(w -> !w.id.equals(from.id))
                .filter(w -> w.active)
                .filter(w -> visibleTo(p, w))
                .filter(w -> !needDiscovery || known.contains(w.id))
                .filter(w -> cross || w.base.getWorld().equals(from.base.getWorld()))
                .filter(w -> favOnly ? favs.contains(w.id) : dimOf(w) == dim)
                .sorted(order)
                .collect(Collectors.toList());
    }

    private ItemStack waystoneItem(Player p, Waystone w, boolean showDim) {
        boolean fav = isFavorite(p, w);
        ItemStack it = new ItemStack(iconFor(p, w));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName((fav ? ChatColor.GOLD + "\u2605 " : "") + w.nameColor + plainName(w));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Owner: " + w.ownerName);
        lore.add(ChatColor.GRAY + "World: " + w.base.getWorld().getName()
                + (showDim ? " (" + dimName(dimOf(w)) + ")" : ""));
        if (w.base.getWorld().equals(p.getWorld())) {
            lore.add(ChatColor.GRAY + "Distance: " + (int) w.base.distance(p.getLocation()) + " blocks");
        }
        if (w.isPrivate) lore.add(ChatColor.RED + "Private");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Left-click: teleport");
        lore.add(ChatColor.YELLOW + "Right-click: " + (fav ? "remove from favorites" : "add to favorites"));
        m.setLore(lore);
        if (fav) m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "tp:" + w.id);
        it.setItemMeta(m);
        return it;
    }

    private void renderTeleport(Player p, Gui g, Waystone from) {
        List<Waystone> list = listFor(p, from, g.dim, g.favOnly, g.sort);
        int pages = Math.max(1, (list.size() + TP_PER_PAGE - 1) / TP_PER_PAGE);
        if (g.page >= pages) g.page = pages - 1;
        if (g.page < 0) g.page = 0;
        Inventory inv = g.inv;

        int start = g.page * TP_PER_PAGE;
        for (int i = 0; i < TP_PER_PAGE && start + i < list.size(); i++) {
            inv.setItem(i, waystoneItem(p, list.get(start + i), g.favOnly));
        }
        if (list.isEmpty()) {
            if (g.favOnly) {
                inv.setItem(13, button(Material.PAPER, ChatColor.GRAY + "No favorites yet", "none", false,
                        ChatColor.DARK_GRAY + "Right-click a waystone in any list",
                        ChatColor.DARK_GRAY + "to add it to your favorites."));
            } else {
                inv.setItem(13, button(Material.PAPER, ChatColor.GRAY + "Nothing here yet", "none", false,
                        ChatColor.DARK_GRAY + "Walk up to " + dimName(g.dim) + " waystones",
                        ChatColor.DARK_GRAY + "and click them to discover them."));
            }
        }

        fill(inv, 36, 54);
        String pageInfo = ChatColor.GRAY + "Page " + (g.page + 1) + "/" + pages;
        if (g.page > 0) inv.setItem(36, button(Material.ARROW, ChatColor.YELLOW + "Previous page", "prev", false, pageInfo));
        if (g.page < pages - 1) inv.setItem(44, button(Material.ARROW, ChatColor.YELLOW + "Next page", "next", false, pageInfo));

        inv.setItem(37, dimTab(Material.GRASS_BLOCK, Dim.OVERWORLD, g, p, from));
        inv.setItem(38, dimTab(Material.NETHERRACK, Dim.NETHER, g, p, from));
        inv.setItem(39, dimTab(Material.END_STONE, Dim.END, g, p, from));

        int favCount = listFor(p, from, g.dim, true, g.sort).size();
        inv.setItem(40, button(Material.NETHER_STAR, ChatColor.GOLD + "Favorites", "fav_tab", g.favOnly,
                ChatColor.GRAY + "" + favCount + " favorite" + (favCount == 1 ? "" : "s"),
                ChatColor.DARK_GRAY + "Right-click any waystone to add it"));

        String sortName = switch (g.sort) {
            case DISTANCE -> "Closest first";
            case NAME -> "Name (A-Z)";
            case NEWEST -> "Newest first";
        };
        inv.setItem(41, button(Material.HOPPER, ChatColor.AQUA + "Sort: " + sortName, "sort", false,
                (g.sort == Sort.DISTANCE ? ChatColor.GREEN + "> " : ChatColor.GRAY + "  ") + "Closest first",
                (g.sort == Sort.NAME ? ChatColor.GREEN + "> " : ChatColor.GRAY + "  ") + "Name (A-Z)",
                (g.sort == Sort.NEWEST ? ChatColor.GREEN + "> " : ChatColor.GRAY + "  ") + "Newest first",
                "", ChatColor.YELLOW + "Click to change", ChatColor.DARK_GRAY + "Favorites always stay on top"));

        boolean favHere = isFavorite(p, from);
        inv.setItem(45, button(Material.GOLD_NUGGET,
                (favHere ? ChatColor.GOLD + "\u2605 " : ChatColor.YELLOW) + "Favorite this waystone", "pin_self", favHere,
                ChatColor.GRAY + plainName(from),
                ChatColor.YELLOW + (favHere ? "Click to remove from favorites" : "Click to add to favorites")));

        inv.setItem(46, button(Material.GOLD_INGOT,
                ChatColor.YELLOW + "Favorite mode: " + (g.favMode ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                "favmode", g.favMode,
                ChatColor.GRAY + "When ON, clicking a waystone adds or",
                ChatColor.GRAY + "removes it from favorites instead of",
                ChatColor.GRAY + "teleporting (handy without right-click)."));
        inv.setItem(47, button(Material.RED_CONCRETE, ChatColor.RED + "Deactivate for me", "forget", false,
                ChatColor.GRAY + "Removes " + plainName(from) + " from your list.",
                ChatColor.GRAY + "Other players can still use it.",
                ChatColor.DARK_GRAY + "Click the waystone again to re-activate it."));
        inv.setItem(49, exitButton());
        if (canManage(p, from)) {
            inv.setItem(53, button(Material.NAME_TAG, ChatColor.GOLD + "Settings", "settings", false,
                    ChatColor.GRAY + "Customize this waystone",
                    ChatColor.DARK_GRAY + "(or sneak + right-click it)"));
        } else {
            inv.setItem(53, button(Material.ITEM_FRAME, ChatColor.GOLD + "My icon", "personal", false,
                    ChatColor.GRAY + "Change how " + plainName(from) + " looks",
                    ChatColor.GRAY + "in your own teleport menu."));
        }
    }

    private ItemStack dimTab(Material mat, Dim dim, Gui g, Player p, Waystone from) {
        int count = listFor(p, from, dim, false, g.sort).size();
        return button(mat, ChatColor.AQUA + dimName(dim), "dim:" + dim.name(), !g.favOnly && g.dim == dim,
                ChatColor.GRAY + "" + count + " waystone" + (count == 1 ? "" : "s"));
    }

    // ---------- public / private choice ----------
    private void renderVisibility(Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 27);
        inv.setItem(4, button(Material.PAPER, ChatColor.AQUA + plainName(w), "none", false,
                ChatColor.GRAY + "Who should be able to use this waystone?"));
        inv.setItem(11, button(Material.EMERALD, ChatColor.GREEN + "" + ChatColor.ITALIC + "Public", "vis:public", false,
                ChatColor.GRAY + "Anyone can find, discover and",
                ChatColor.GRAY + "teleport to this waystone."));
        inv.setItem(15, button(Material.IRON_DOOR, ChatColor.RED + "" + ChatColor.ITALIC + "Private", "vis:private", false,
                ChatColor.GRAY + "Only you (and anyone you whitelist)",
                ChatColor.GRAY + "can see and use it."));
        inv.setItem(22, button(Material.PAPER, ChatColor.DARK_GRAY + "Closing this keeps it Public", "none", false,
                ChatColor.DARK_GRAY + "You can change it later in Settings."));
        inv.setItem(26, exitButton());
    }

    // ---------- settings ----------
    private void renderSettings(Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 36);
        inv.setItem(4, button(Material.PAPER, w.nameColor + plainName(w), "none", false,
                ChatColor.GRAY + "Owner: " + w.ownerName,
                ChatColor.GRAY + "Status: " + (w.active ? ChatColor.GREEN + "Active" : ChatColor.YELLOW + "Not activated"),
                ChatColor.GRAY + "Access: " + (w.isPrivate ? ChatColor.RED + "Private" : ChatColor.GREEN + "Public")));
        inv.setItem(10, button(Material.NAME_TAG, ChatColor.YELLOW + "Rename", "rename", false,
                ChatColor.GRAY + "Type a new name in an anvil screen"));
        if (w.isPrivate) {
            inv.setItem(11, button(Material.IRON_DOOR, ChatColor.RED + "" + ChatColor.ITALIC + "Private", "vis:toggle", false,
                    ChatColor.GRAY + "Only you and your whitelist can use it.",
                    ChatColor.YELLOW + "Click to make it Public"));
        } else {
            inv.setItem(11, button(Material.EMERALD, ChatColor.GREEN + "" + ChatColor.ITALIC + "Public", "vis:toggle", false,
                    ChatColor.GRAY + "Anyone can discover and use it.",
                    ChatColor.YELLOW + "Click to make it Private"));
        }
        inv.setItem(12, button(w.icon, ChatColor.AQUA + "Menu icon", "icon_menu", false,
                ChatColor.GRAY + "The item shown in the teleport menu",
                ChatColor.YELLOW + "Click to change"));
        inv.setItem(13, button(Material.WRITABLE_BOOK, ChatColor.AQUA + "Whitelist", "wl_menu", false,
                ChatColor.GRAY + "Players allowed to use this waystone",
                ChatColor.GRAY + "when it is Private (" + w.whitelist.size() + ")",
                ChatColor.YELLOW + "Click to edit"));
        inv.setItem(14, button(Material.GLOW_ITEM_FRAME, ChatColor.AQUA + "Hologram", "holo_menu", false,
                ChatColor.GRAY + "Name color and floating item",
                ChatColor.YELLOW + "Click to edit"));
        if (!w.active) {
            inv.setItem(16, button(Material.LIME_CONCRETE, ChatColor.GREEN + "Activate", "activate", true,
                    ChatColor.GRAY + "Turn this waystone on so it can be used"));
        } else {
            inv.setItem(16, button(Material.BEACON, ChatColor.GREEN + "Active", "none", false,
                    ChatColor.GRAY + "This waystone is working.",
                    ChatColor.DARK_GRAY + "Each player can deactivate it for",
                    ChatColor.DARK_GRAY + "themselves from the teleport menu."));
        }
        inv.setItem(31, button(Material.PISTON, ChatColor.GOLD + "Dismantle", "dismantle_menu", false,
                ChatColor.GRAY + "Pack it up to move it, or delete it"));
        inv.setItem(35, exitButton());
    }

    // ---------- icon picker ----------
    private void renderIcons(Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 45);
        inv.setItem(4, button(w.icon, ChatColor.AQUA + "Current icon", "none", false,
                ChatColor.GRAY + pretty(w.icon)));
        for (int i = 0; i < ICON_PRESETS.length; i++) {
            Material m = ICON_PRESETS[i];
            inv.setItem(ICON_SLOTS[i], button(m, ChatColor.WHITE + pretty(m), "icon:" + m.name(), w.icon == m,
                    ChatColor.YELLOW + "Click to use"));
        }
        inv.setItem(38, button(Material.HOPPER, ChatColor.AQUA + "Use the item in your hand", "icon_hand", false,
                ChatColor.GRAY + "Hold any item, then click here"));
        inv.setItem(42, button(Material.ARROW, ChatColor.YELLOW + "Back", "back", false));
        inv.setItem(44, exitButton());
    }

    // ---------- whitelist ----------
    private void renderWhitelist(Gui g, Waystone w) {
        Inventory inv = g.inv;
        List<UUID> listed = new ArrayList<>(w.whitelist);
        listed.sort(Comparator.comparing(id -> nameOf(id).toLowerCase()));
        List<UUID> others = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            UUID id = o.getUniqueId();
            if (!id.equals(w.owner) && !w.whitelist.contains(id)) others.add(id);
        }
        others.sort(Comparator.comparing(id -> nameOf(id).toLowerCase()));
        List<UUID> all = new ArrayList<>(listed);
        all.addAll(others);

        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        if (g.page >= pages) g.page = pages - 1;
        if (g.page < 0) g.page = 0;
        int start = g.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < all.size(); i++) {
            UUID id = all.get(start + i);
            boolean on = w.whitelist.contains(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            m.setDisplayName((on ? ChatColor.GREEN : ChatColor.WHITE) + nameOf(id));
            m.setLore(List.of(on ? ChatColor.GREEN + "Whitelisted" : ChatColor.GRAY + "Not whitelisted",
                    ChatColor.YELLOW + (on ? "Click to remove" : "Click to add")));
            if (on) m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "wl:" + id);
            head.setItemMeta(m);
            inv.setItem(i, head);
        }
        fill(inv, 45, 54);
        String pageInfo = ChatColor.GRAY + "Page " + (g.page + 1) + "/" + pages;
        if (g.page > 0) inv.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Previous page", "prev", false, pageInfo));
        if (g.page < pages - 1) inv.setItem(53, button(Material.ARROW, ChatColor.YELLOW + "Next page", "next", false, pageInfo));
        inv.setItem(47, button(Material.PAPER, ChatColor.AQUA + "Whitelist", "none", false,
                ChatColor.GRAY + "Whitelisted players can use this",
                ChatColor.GRAY + "waystone even when it is Private.",
                ChatColor.GRAY + "Online players are listed so you can add them."));
        inv.setItem(49, button(Material.ARROW, ChatColor.YELLOW + "Back", "back", false));
        inv.setItem(51, exitButton());
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    // ---------- hologram settings ----------
    private void renderHologram(Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 45);
        inv.setItem(4, button(w.holoItem == null ? Material.BARRIER : w.holoItem,
                ChatColor.AQUA + "Floating item",
                "none", false,
                ChatColor.GRAY + (w.holoItem == null ? "None" : pretty(w.holoItem)
                        + (w.holoItemOn ? " (shown)" : " (hidden)"))));
        for (int i = 0; i < PALETTE.length; i++) {
            ChatColor c = PALETTE[i];
            inv.setItem(PALETTE_SLOTS[i], button(PALETTE_MATS[i], c + "" + ChatColor.ITALIC + "Name color",
                    "color:" + c.name(), w.nameColor == c,
                    ChatColor.GRAY + "Make the name look like this",
                    ChatColor.YELLOW + "Click to use"));
        }
        inv.setItem(29, button(w.holoItemOn ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.AQUA + "Floating item: " + (w.holoItemOn ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                "holo_toggle", false,
                ChatColor.GRAY + "Show or hide the spinning item",
                ChatColor.YELLOW + "Click to toggle"));
        inv.setItem(31, button(Material.ITEM_FRAME, ChatColor.AQUA + "Use the item in your hand", "holo_hand", false,
                ChatColor.GRAY + "Hold any item, then click here",
                ChatColor.GRAY + "It will float and spin above the waystone"));
        inv.setItem(33, button(Material.BARRIER, ChatColor.RED + "Remove floating item", "holo_remove", false));
        inv.setItem(40, button(Material.ARROW, ChatColor.YELLOW + "Back", "back", false));
        inv.setItem(44, exitButton());
    }

    // ---------- dismantle ----------
    private void renderDismantle(Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 27);
        inv.setItem(11, button(Material.CHEST, ChatColor.GREEN + "Pack up", "pack", false,
                ChatColor.GRAY + "Get a waystone item that keeps its name",
                ChatColor.GRAY + "and settings. Place it to move it."));
        inv.setItem(13, button(Material.ARROW, ChatColor.YELLOW + "Cancel", "back", false));
        inv.setItem(22, exitButton());
        inv.setItem(15, button(Material.TNT, ChatColor.RED + "Delete completely", "delete", false,
                ChatColor.GRAY + "Removes the waystone and its settings.",
                ChatColor.GRAY + "You get a fresh waystone item back."));
    }

    // ---------- personal icon (any player) ----------
    private void renderPersonal(Player p, Gui g, Waystone w) {
        Inventory inv = g.inv;
        fill(inv, 0, 27);
        Material cur = iconFor(p, w);
        inv.setItem(4, button(cur, ChatColor.AQUA + "Your icon for " + plainName(w), "none", false,
                ChatColor.GRAY + pretty(cur),
                ChatColor.DARK_GRAY + "Only you see this in your teleport menu."));
        inv.setItem(11, button(Material.ITEM_FRAME, ChatColor.YELLOW + "Use the item in your hand", "my_icon_hand", false,
                ChatColor.GRAY + "Hold the item you want,",
                ChatColor.GRAY + "then click here."));
        inv.setItem(15, button(Material.BUCKET, ChatColor.YELLOW + "Reset to default", "my_icon_reset", false,
                ChatColor.GRAY + "Use the icon the owner picked."));
        inv.setItem(22, exitButton());
    }

    // ---------- click handling ----------
    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String action = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.equals("none")) return;
        Player p = (Player) e.getWhoClicked();
        Waystone w = waystones.get(g.waystone);
        if (w == null) {
            p.closeInventory();
            return;
        }
        handleAction(p, g, w, action, e.isRightClick() || e.isShiftClick());
    }

    private void handleAction(Player p, Gui g, Waystone w, String action, boolean altClick) {
        String[] parts = action.split(":", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        boolean manage = canManage(p, w);

        switch (parts[0]) {
            case "prev" -> { g.page--; render(p, g); }
            case "next" -> { g.page++; render(p, g); }
            case "dim" -> {
                g.dim = Dim.valueOf(arg);
                g.favOnly = false;
                g.page = 0;
                render(p, g);
            }
            case "sort" -> {
                g.sort = Sort.values()[(g.sort.ordinal() + 1) % Sort.values().length];
                g.page = 0;
                render(p, g);
            }
            case "tp" -> {
                Waystone dest = waystones.get(UUID.fromString(arg));
                if (dest == null || !dest.active || !visibleTo(p, dest)) {
                    p.closeInventory();
                    p.sendMessage(msg(ChatColor.RED + "That waystone is no longer available."));
                    return;
                }
                if (altClick || g.favMode) {
                    boolean now = toggleFavorite(p, dest);
                    sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, now ? 1.5f : 0.8f);
                    render(p, g);
                } else {
                    p.closeInventory();
                    startTeleport(p, dest);
                }
            }
            case "favmode" -> {
                g.favMode = !g.favMode;
                sound(p, Sound.BLOCK_LEVER_CLICK, 1f);
                render(p, g);
            }
            case "personal" -> {
                if (visibleTo(p, w)) later(() -> openGui(p, Kind.PERSONAL, w));
            }
            case "my_icon_hand" -> {
                if (!visibleTo(p, w)) return;
                Material held = p.getInventory().getItemInMainHand().getType();
                if (held.isAir() || !held.isItem()) {
                    p.sendMessage(msg(ChatColor.RED + "Hold an item in your main hand first."));
                    return;
                }
                personalIcons.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(w.id, held);
                save();
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                render(p, g);
            }
            case "my_icon_reset" -> {
                Map<UUID, Material> mine = personalIcons.get(p.getUniqueId());
                if (mine != null) mine.remove(w.id);
                save();
                sound(p, Sound.UI_BUTTON_CLICK, 0.8f);
                render(p, g);
            }
            case "fav_tab" -> {
                g.favOnly = true;
                g.page = 0;
                render(p, g);
            }
            case "pin_self" -> {
                boolean now = toggleFavorite(p, w);
                sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, now ? 1.5f : 0.8f);
                p.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET
                        + (now ? " added to" : " removed from") + " your favorites."));
                render(p, g);
            }
            case "close" -> p.closeInventory();
            case "forget" -> {
                discovered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).remove(w.id);
                Set<UUID> favs = favorites.get(p.getUniqueId());
                if (favs != null) favs.remove(w.id);
                save();
                applyVisibility(p, w);
                p.closeInventory();
                sound(p, Sound.BLOCK_BEACON_DEACTIVATE, 1f);
                p.sendMessage(msg(ChatColor.RED + plainName(w) + ChatColor.RESET
                        + " was deactivated for you. Other players can still use it."));
                p.sendMessage(msg(ChatColor.GRAY + "Click it again any time to re-activate it for yourself."));
            }
            case "settings" -> {
                if (manage) later(() -> openGui(p, Kind.SETTINGS, w));
            }
            case "back" -> {
                if (manage) later(() -> openGui(p, Kind.SETTINGS, w));
            }
            case "vis" -> {
                if (!manage) return;
                if (arg.equals("public")) w.isPrivate = false;
                else if (arg.equals("private")) w.isPrivate = true;
                else w.isPrivate = !w.isPrivate;
                save();
                updateHolo(w);
                sound(p, Sound.BLOCK_LEVER_CLICK, 1f);
                p.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET + " is now "
                        + (w.isPrivate ? ChatColor.RED + "private" : ChatColor.GREEN + "public") + ChatColor.RESET + "."));
                if (g.kind == Kind.VISIBILITY) p.closeInventory();
                else render(p, g);
            }
            case "rename" -> {
                if (!manage) return;
                p.closeInventory();
                later(() -> openAnvil(p, w, false));
            }
            case "icon_menu" -> {
                if (manage) later(() -> openGui(p, Kind.ICON, w));
            }
            case "wl_menu" -> {
                if (manage) later(() -> openGui(p, Kind.WHITELIST, w));
            }
            case "holo_menu" -> {
                if (manage) later(() -> openGui(p, Kind.HOLOGRAM, w));
            }
            case "dismantle_menu" -> {
                if (manage) later(() -> openGui(p, Kind.DISMANTLE, w));
            }
            case "activate" -> {
                if (!manage) return;
                activate(p, w);
                render(p, g);
            }
            case "icon" -> {
                if (!manage) return;
                Material m = Material.matchMaterial(arg);
                if (m == null) return;
                w.icon = m;
                save();
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                later(() -> openGui(p, Kind.SETTINGS, w));
            }
            case "icon_hand" -> {
                if (!manage) return;
                Material m = p.getInventory().getItemInMainHand().getType();
                if (m.isAir() || !m.isItem()) {
                    p.sendMessage(msg(ChatColor.RED + "Hold an item in your main hand first."));
                    return;
                }
                w.icon = m;
                save();
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                later(() -> openGui(p, Kind.SETTINGS, w));
            }
            case "wl" -> {
                if (!manage) return;
                UUID id = UUID.fromString(arg);
                if (!w.whitelist.remove(id)) w.whitelist.add(id);
                save();
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                render(p, g);
            }
            case "color" -> {
                if (!manage) return;
                w.nameColor = ChatColor.valueOf(arg);
                save();
                updateHolo(w);
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                render(p, g);
            }
            case "holo_toggle" -> {
                if (!manage) return;
                if (w.holoItem == null) {
                    p.sendMessage(msg(ChatColor.RED + "Pick a floating item first (hold one and click the frame)."));
                    return;
                }
                w.holoItemOn = !w.holoItemOn;
                save();
                updateHolo(w);
                sound(p, Sound.BLOCK_LEVER_CLICK, 1f);
                render(p, g);
            }
            case "holo_hand" -> {
                if (!manage) return;
                Material m = p.getInventory().getItemInMainHand().getType();
                if (m.isAir() || !m.isItem()) {
                    p.sendMessage(msg(ChatColor.RED + "Hold an item in your main hand first."));
                    return;
                }
                w.holoItem = m;
                w.holoItemOn = true;
                save();
                updateHolo(w);
                sound(p, Sound.UI_BUTTON_CLICK, 1f);
                render(p, g);
            }
            case "holo_remove" -> {
                if (!manage) return;
                w.holoItem = null;
                w.holoItemOn = false;
                save();
                updateHolo(w);
                sound(p, Sound.UI_BUTTON_CLICK, 0.8f);
                render(p, g);
            }
            case "pack" -> dismantle(p, w, true);
            case "delete" -> dismantle(p, w, false);
            default -> { }
        }
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Gui) {
            e.setCancelled(true);
            return;
        }
        if (anvilSessions.containsKey(e.getWhoClicked().getUniqueId())
                && e.getView().getTopInventory().getType() == InventoryType.ANVIL) {
            e.setCancelled(true);
        }
    }

    private void dismantle(Player p, Waystone w, boolean keepData) {
        if (!canManage(p, w)) return;
        ItemStack item = keepData ? createPackedItem(w) : createItem();
        Location at = w.base.clone().add(0.5, 0.5, 0.5);
        if (keepData) {
            unindex(w);
            for (int i = 0; i < 3; i++) w.base.clone().add(0, i, 0).getBlock().setType(Material.AIR);
            save();
        } else {
            deleteRecord(w);
        }
        p.closeInventory();
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            at.getWorld().dropItemNaturally(at, left);
        }
        at.getWorld().playSound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.7f);
        p.sendMessage(msg(keepData
                ? "Waystone packed up. Place the item to move it."
                : "Waystone deleted. You got a fresh waystone item back."));
    }

    // ====================================================================
    // Teleporting: warmup, safety, companions
    // ====================================================================
    private void startTeleport(Player p, Waystone dest) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("cooldown-seconds", 5) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("waystone.admin")) {
            long left = (cd - (now - last) + 999) / 1000;
            p.sendMessage(msg(ChatColor.RED + "Wait " + left + "s before teleporting again."));
            return;
        }
        if (warmups.containsKey(p.getUniqueId())) {
            p.sendMessage(msg(ChatColor.RED + "You are already getting ready to teleport."));
            return;
        }
        // safety check when the destination is selected
        Spot spot = findSafe(dest, p);
        if (spot.loc == null) {
            notifyUnsafe(p, dest, spot.reason);
            return;
        }
        int seconds = Math.max(0, getConfig().getInt("warmup-seconds", 3));
        if (seconds == 0) {
            completeTeleport(p, dest);
            return;
        }

        final Location start = p.getLocation().clone();
        final double tol = p.isInsideVehicle() ? 4.0 : 0.5;
        final int totalTicks = seconds * 20;
        BukkitRunnable r = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    BukkitTask t = warmups.remove(p.getUniqueId());
                    if (t != null) t.cancel();
                    cancel();
                    return;
                }
                if (!p.getWorld().equals(start.getWorld()) || p.getLocation().distanceSquared(start) > tol) {
                    cancelWarmup(p, "you moved");
                    return;
                }
                if (ticks % 20 == 0) {
                    int left = seconds - ticks / 20;
                    p.sendActionBar(Component.text("Teleporting in " + left + "... stand still!", NamedTextColor.AQUA));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
                }
                p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 8, 0.4, 0.6, 0.4, 0.3);
                ticks += 2;
                if (ticks >= totalTicks) {
                    warmups.remove(p.getUniqueId());
                    cancel();
                    completeTeleport(p, dest);
                }
            }
        };
        warmups.put(p.getUniqueId(), r.runTaskTimer(this, 0L, 2L));
    }

    private void cancelWarmup(Player p, String reason) {
        BukkitTask t = warmups.remove(p.getUniqueId());
        if (t == null) return;
        t.cancel();
        if (reason != null) {
            p.sendMessage(msg(ChatColor.RED + "Teleport cancelled: " + reason + "."));
            p.sendActionBar(Component.text("Teleport cancelled", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!warmups.containsKey(p.getUniqueId())) return;
        if (e.getFinalDamage() <= 0) return;
        cancelWarmup(p, "you took damage");
    }

    private void notifyUnsafe(Player p, Waystone dest, String reason) {
        p.sendMessage(msg(ChatColor.RED + "Teleport cancelled! " + plainName(dest)
                + " isn't safe to teleport to: " + reason + "."));
        p.sendActionBar(Component.text("Unsafe destination - teleport cancelled", NamedTextColor.RED));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
    }

    /** Runs after the warmup: checks everything again before moving anyone. */
    private void completeTeleport(Player p, Waystone dest) {
        if (!p.isOnline()) return;
        if (waystones.get(dest.id) == null || !dest.active) {
            p.sendMessage(msg(ChatColor.RED + "Teleport cancelled: that waystone is no longer available."));
            return;
        }
        Spot spot = findSafe(dest, p);
        if (spot.loc == null) {
            notifyUnsafe(p, dest, spot.reason);
            return;
        }
        doTeleport(p, dest, spot.loc);
    }

    private Hazard hazard(Block feet) {
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        for (Block b : new Block[]{feet, head, ground}) {
            if (HAZARDS.contains(b.getType())) {
                return new Hazard(pretty(b.getType()) + " at the destination", true);
            }
        }
        if (!feet.isPassable() || !head.isPassable()) return new Hazard("the destination is blocked", false);
        if (head.isLiquid()) return new Hazard("the destination is underwater", true);
        if (!ground.getType().isSolid()) {
            boolean found = false;
            for (int i = 2; i <= 5; i++) {
                Block b = feet.getRelative(0, -i, 0);
                if (HAZARDS.contains(b.getType())) {
                    return new Hazard(pretty(b.getType()) + " below the destination", true);
                }
                if (b.getType().isSolid() || b.isLiquid()) {
                    found = true;
                    break;
                }
            }
            if (!found) return new Hazard("there is no solid ground (void or a long drop)", true);
        }
        return null;
    }

    private Spot findSafe(Waystone w, Player p) {
        Spot spot = new Spot();
        World world = w.base.getWorld();
        if (world == null) {
            spot.reason = "that world isn't loaded";
            return spot;
        }
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}, {0, 3, 0}};
        Block baseBlock = w.base.getBlock();
        Hazard best = null;
        for (int[] o : offsets) {
            Block feet = baseBlock.getRelative(o[0], o[1], o[2]);
            Hazard h = hazard(feet);
            if (h == null) {
                Location l = feet.getLocation().add(0.5, 0, 0.5);
                l.setYaw(p.getLocation().getYaw());
                l.setPitch(p.getLocation().getPitch());
                spot.loc = l;
                return spot;
            }
            if (best == null || (h.danger() && !best.danger())) best = h;
        }
        spot.reason = best == null ? "no safe spot was found" : best.text();
        return spot;
    }

    private void collectSeats(Entity v, List<Seat> out) {
        for (Entity c : v.getPassengers()) {
            out.add(new Seat(v, c));
            collectSeats(c, out);
        }
    }

    private List<Entity> collectPets(Player p, Set<Entity> exclude) {
        List<Entity> out = new ArrayList<>();
        double range = getConfig().getDouble("pet-range", 15.0);
        boolean followers = getConfig().getBoolean("bring-following-pets", true);
        for (Entity en : p.getNearbyEntities(range, range, range)) {
            if (!(en instanceof LivingEntity le) || exclude.contains(en)) continue;
            boolean leashed = le.isLeashed() && p.equals(le.getLeashHolder());
            boolean follower = false;
            if (followers && en instanceof Tameable t && t.isTamed()
                    && p.getUniqueId().equals(t.getOwnerUniqueId()) && !(en instanceof AbstractHorse)) {
                follower = !(en instanceof Sittable s && s.isSitting());
            }
            if (leashed || follower) out.add(en);
        }
        return out;
    }

    private void doTeleport(Player p, Waystone dest, Location target) {
        Entity rootVehicle = null;
        if (p.isInsideVehicle()) {
            rootVehicle = p.getVehicle();
            while (rootVehicle.getVehicle() != null) rootVehicle = rootVehicle.getVehicle();
        }
        final Entity vehicle = rootVehicle;
        final List<Seat> seats = new ArrayList<>();
        Set<Entity> group = new HashSet<>();
        if (vehicle != null) {
            collectSeats(vehicle, seats);
            group.add(vehicle);
            for (Seat s : seats) group.add(s.child);
        } else {
            group.add(p);
        }
        List<Entity> pets = collectPets(p, group);

        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.5);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);

        if (vehicle != null) {
            for (Seat s : seats) s.child.leaveVehicle();
            vehicle.teleport(target);
            for (Seat s : seats) s.child.teleport(target);
            later2(() -> {
                for (Seat s : seats) {
                    if (s.parent.isValid() && s.child.isValid()) s.parent.addPassenger(s.child);
                }
            });
        } else {
            p.teleport(target);
        }
        for (Entity pet : pets) pet.teleport(target);
        cooldowns.put(p.getUniqueId(), System.currentTimeMillis());

        arrivalEffect(target);

        int extras = pets.size() + (vehicle != null ? 1 : 0);
        for (Seat s : seats) {
            if (s.child instanceof Player pp && !pp.equals(p)) {
                pp.sendMessage(msg("You travelled with " + ChatColor.GREEN + p.getName() + ChatColor.RESET
                        + " to " + ChatColor.GREEN + plainName(dest) + ChatColor.RESET + "."));
            } else if (!(s.child instanceof Player)) {
                extras++;
            }
        }
        p.sendMessage(msg("Teleported to " + ChatColor.GREEN + plainName(dest) + ChatColor.RESET
                + (extras > 0 ? ChatColor.GRAY + " (companions came along)" : "") + "."));
    }

    /** Burst plus a short rising double spiral where the player arrives. */
    private void arrivalEffect(Location loc) {
        World world = loc.getWorld();
        Location c = loc.clone().add(0, 1, 0);
        world.spawnParticle(Particle.PORTAL, c, 80, 0.5, 0.9, 0.5, 0.6);
        world.spawnParticle(Particle.END_ROD, c, 40, 0.4, 0.8, 0.4, 0.08);
        world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 14) {
                    cancel();
                    return;
                }
                double y = t * 0.16;
                for (int arm = 0; arm < 2; arm++) {
                    double a = t * 0.7 + arm * Math.PI;
                    world.spawnParticle(Particle.END_ROD,
                            loc.getX() + Math.cos(a) * 0.9, loc.getY() + y, loc.getZ() + Math.sin(a) * 0.9,
                            1, 0, 0, 0, 0);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void later2(Runnable r) {
        getServer().getScheduler().runTaskLater(this, r, 3L);
    }

    // ====================================================================
    // Optional commands
    // ====================================================================
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("admin")) return handleAdmin(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (a.length == 0) {
            p.sendMessage(msg("Everything works through menus. Optional shortcuts:"));
            p.sendMessage(msg("/waystone settings  - open settings for the waystone you're looking at"));
            p.sendMessage(msg("/waystone rename <name>, /waystone private, /waystone public"));
            p.sendMessage(msg("/waystone favorite, /waystone list"));
            if (p.hasPermission("waystone.admin")) p.sendMessage(msg(ChatColor.GOLD + "/waystone admin help" + ChatColor.RESET + " - operator tools"));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "settings": {
                Waystone w = lookedAt(p);
                if (w == null || !canManage(p, w)) {
                    p.sendMessage(msg(ChatColor.RED + "Look at one of your waystones (within 6 blocks)."));
                    return true;
                }
                openGui(p, Kind.SETTINGS, w);
                return true;
            }
            case "rename": {
                if (a.length < 2) {
                    p.sendMessage(msg(ChatColor.RED + "Usage: /waystone rename <name>"));
                    return true;
                }
                Waystone w = lookedAt(p);
                if (w == null || !canManage(p, w)) {
                    p.sendMessage(msg(ChatColor.RED + "Look at one of your waystones (within 6 blocks)."));
                    return true;
                }
                String name = sanitizeName(String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
                if (name.isEmpty() || name.length() > 32) {
                    p.sendMessage(msg(ChatColor.RED + "Names must be 1-32 characters."));
                    return true;
                }
                w.name = name;
                save();
                updateHolo(w);
                p.sendMessage(msg("Renamed to " + ChatColor.GREEN + name + ChatColor.RESET + "."));
                return true;
            }
            case "private":
            case "public": {
                Waystone w = lookedAt(p);
                if (w == null || !canManage(p, w)) {
                    p.sendMessage(msg(ChatColor.RED + "Look at one of your waystones (within 6 blocks)."));
                    return true;
                }
                w.isPrivate = a[0].equalsIgnoreCase("private");
                save();
                updateHolo(w);
                p.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET + " is now "
                        + (w.isPrivate ? ChatColor.RED + "private" : ChatColor.GREEN + "public") + ChatColor.RESET + "."));
                return true;
            }
            case "favorite":
            case "fav": {
                Waystone w = lookedAt(p);
                if (w == null || !visibleTo(p, w)) {
                    p.sendMessage(msg(ChatColor.RED + "Look at a waystone within 6 blocks."));
                    return true;
                }
                boolean now = toggleFavorite(p, w);
                p.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET
                        + (now ? " pinned to" : " removed from") + " your favorites."));
                return true;
            }
            case "list": {
                Set<UUID> known = discovered.getOrDefault(p.getUniqueId(), Collections.emptySet());
                List<Waystone> list = waystones.values().stream()
                        .filter(w -> w.active && visibleTo(p, w))
                        .filter(w -> known.contains(w.id))
                        .collect(Collectors.toList());
                p.sendMessage(msg("You can travel to " + list.size() + " waystone(s):"));
                for (Waystone w : list) {
                    p.sendMessage(ChatColor.GRAY + " - " + (isFavorite(p, w) ? ChatColor.GOLD + "\u2605 " : "")
                            + ChatColor.GREEN + plainName(w) + ChatColor.GRAY
                            + (w.isPrivate ? ChatColor.RED + " [private]" + ChatColor.GRAY : "")
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
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("settings", "rename", "private", "public", "favorite", "list"));
            if (s.hasPermission("waystone.admin")) subs.add("admin");
            return subs.stream().filter(x -> x.startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        }
        if (a[0].equalsIgnoreCase("admin") && s.hasPermission("waystone.admin")) {
            if (a.length == 2) {
                return List.of("help", "list", "info", "tp", "remove", "removeall", "rename", "public", "private",
                        "activate", "deactivate", "resetcounter", "setcounter", "forget", "reload").stream()
                        .filter(x -> x.startsWith(a[1].toLowerCase())).collect(Collectors.toList());
            }
            if (a.length == 3) {
                String sub = a[1].toLowerCase();
                if (sub.equals("list") || sub.equals("removeall") || sub.equals("forget")) {
                    return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                            .filter(x -> x.toLowerCase().startsWith(a[2].toLowerCase())).collect(Collectors.toList());
                }
                return waystones.values().stream().map(this::shortId)
                        .filter(x -> x.startsWith(a[2].toLowerCase())).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    // ====================================================================
    // Operator commands: /waystone admin ...
    // ====================================================================
    private String shortId(Waystone w) {
        return w.id.toString().substring(0, 8);
    }

    /** Finds a waystone by id prefix (min 3 chars) or by exact one-word name. */
    private Waystone findWaystone(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        String low = ref.toLowerCase();
        List<Waystone> hits = new ArrayList<>();
        if (low.length() >= 3) {
            for (Waystone w : waystones.values()) {
                if (w.id.toString().startsWith(low)) hits.add(w);
            }
        }
        if (hits.isEmpty()) {
            for (Waystone w : waystones.values()) {
                if (plainName(w).equalsIgnoreCase(ref)) hits.add(w);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private Waystone adminTarget(CommandSender s, String[] a, int idx) {
        if (a.length > idx) {
            Waystone w = findWaystone(a[idx]);
            if (w == null) {
                s.sendMessage(msg(ChatColor.RED + "No single waystone matches \"" + a[idx]
                        + "\". Use /waystone admin list to see ids."));
            }
            return w;
        }
        if (s instanceof Player pl) {
            Waystone w = lookedAt(pl);
            if (w == null) s.sendMessage(msg(ChatColor.RED + "Look at a waystone, or give its id."));
            return w;
        }
        s.sendMessage(msg(ChatColor.RED + "Give a waystone id (see /waystone admin list)."));
        return null;
    }

    private void adminHelp(CommandSender s) {
        s.sendMessage(msg(ChatColor.GOLD + "Operator commands " + ChatColor.GRAY
                + "(<id> = first 8 characters from the list, or look at a waystone)"));
        s.sendMessage(ChatColor.GRAY + " /waystone admin list [player]");
        s.sendMessage(ChatColor.GRAY + " /waystone admin info [id]");
        s.sendMessage(ChatColor.GRAY + " /waystone admin tp [id]");
        s.sendMessage(ChatColor.GRAY + " /waystone admin remove [id]");
        s.sendMessage(ChatColor.GRAY + " /waystone admin removeall <player> confirm");
        s.sendMessage(ChatColor.GRAY + " /waystone admin rename <id> <new name>");
        s.sendMessage(ChatColor.GRAY + " /waystone admin public|private [id]");
        s.sendMessage(ChatColor.GRAY + " /waystone admin activate|deactivate [id]  (for everyone)");
        s.sendMessage(ChatColor.GRAY + " /waystone admin resetcounter   (next default name is Waystone 1)");
        s.sendMessage(ChatColor.GRAY + " /waystone admin setcounter <number>");
        s.sendMessage(ChatColor.GRAY + " /waystone admin forget <player> [id]  (reset what a player discovered)");
        s.sendMessage(ChatColor.GRAY + " /waystone admin reload");
    }

    private boolean handleAdmin(CommandSender s, String[] a) {
        if (!s.hasPermission("waystone.admin")) {
            s.sendMessage(msg(ChatColor.RED + "You don't have permission to do that."));
            return true;
        }
        String sub = a.length > 1 ? a[1].toLowerCase() : "help";
        switch (sub) {
            case "list" -> {
                String filter = a.length > 2 ? a[2] : null;
                List<Waystone> list = waystones.values().stream()
                        .filter(w -> filter == null || w.ownerName.equalsIgnoreCase(filter))
                        .collect(Collectors.toList());
                s.sendMessage(msg(list.size() + " waystone(s)" + (filter == null ? "" : " owned by " + filter)
                        + ". Next default name: Waystone " + (counter + 1)));
                int shown = 0;
                for (Waystone w : list) {
                    if (shown++ >= 40) {
                        s.sendMessage(ChatColor.GRAY + "... and " + (list.size() - 40)
                                + " more. Filter with /waystone admin list <player>");
                        break;
                    }
                    s.sendMessage(ChatColor.GRAY + "[" + shortId(w) + "] " + ChatColor.GREEN + plainName(w)
                            + ChatColor.GRAY + " - " + w.ownerName + ", " + w.base.getWorld().getName() + " "
                            + w.base.getBlockX() + "," + w.base.getBlockY() + "," + w.base.getBlockZ()
                            + (w.isPrivate ? ChatColor.RED + " [private]" : "")
                            + (w.active ? "" : ChatColor.YELLOW + " [inactive]"));
                }
            }
            case "info" -> {
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                int seen = 0;
                for (Set<UUID> set : discovered.values()) {
                    if (set.contains(w.id)) seen++;
                }
                s.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.GRAY + " [" + w.id + "]"));
                s.sendMessage(ChatColor.GRAY + " Owner: " + w.ownerName + " (" + w.owner + ")");
                s.sendMessage(ChatColor.GRAY + " Location: " + w.base.getWorld().getName() + " "
                        + w.base.getBlockX() + ", " + w.base.getBlockY() + ", " + w.base.getBlockZ());
                s.sendMessage(ChatColor.GRAY + " Active: " + w.active + " | Private: " + w.isPrivate
                        + " | Whitelisted: " + w.whitelist.size() + " | Discovered by: " + seen + " player(s)");
                s.sendMessage(ChatColor.GRAY + " Created: " + new Date(w.created));
            }
            case "tp", "teleport" -> {
                if (!(s instanceof Player pl)) {
                    s.sendMessage("Players only.");
                    return true;
                }
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                Spot spot = findSafe(w, pl);
                Location loc = spot.loc != null ? spot.loc : w.base.clone().add(0.5, 3, 0.5);
                pl.teleport(loc);
                s.sendMessage(msg("Teleported to " + ChatColor.GREEN + plainName(w) + ChatColor.RESET + "."));
            }
            case "remove", "delete" -> {
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                String name = plainName(w);
                deleteRecord(w);
                s.sendMessage(msg("Removed waystone " + ChatColor.GREEN + name + ChatColor.RESET + " (" + w.ownerName + ")."));
            }
            case "removeall" -> {
                if (a.length < 3) {
                    s.sendMessage(msg(ChatColor.RED + "Usage: /waystone admin removeall <player> confirm"));
                    return true;
                }
                List<Waystone> mine = waystones.values().stream()
                        .filter(w -> w.ownerName.equalsIgnoreCase(a[2]))
                        .collect(Collectors.toList());
                if (a.length < 4 || !a[3].equalsIgnoreCase("confirm")) {
                    s.sendMessage(msg(ChatColor.YELLOW + "This would delete " + mine.size() + " waystone(s) owned by "
                            + a[2] + ". Run it again with " + ChatColor.WHITE + "confirm" + ChatColor.YELLOW + " at the end."));
                    return true;
                }
                for (Waystone w : mine) deleteRecord(w);
                s.sendMessage(msg("Removed " + mine.size() + " waystone(s) owned by " + a[2] + "."));
            }
            case "rename" -> {
                if (a.length < 4) {
                    s.sendMessage(msg(ChatColor.RED + "Usage: /waystone admin rename <id> <new name>"));
                    return true;
                }
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                String name = sanitizeName(String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
                if (name.isEmpty() || name.length() > 32) {
                    s.sendMessage(msg(ChatColor.RED + "Names must be 1-32 characters."));
                    return true;
                }
                w.name = name;
                save();
                updateHolo(w);
                s.sendMessage(msg("Renamed to " + ChatColor.GREEN + name + ChatColor.RESET + "."));
            }
            case "public", "private" -> {
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                w.isPrivate = sub.equals("private");
                save();
                updateHolo(w);
                s.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET + " is now " + sub + "."));
            }
            case "activate", "deactivate" -> {
                Waystone w = adminTarget(s, a, 2);
                if (w == null) return true;
                w.active = sub.equals("activate");
                if (w.active) discovered.computeIfAbsent(w.owner, k -> new HashSet<>()).add(w.id);
                save();
                updateHolo(w);
                s.sendMessage(msg(ChatColor.GREEN + plainName(w) + ChatColor.RESET + " was "
                        + (w.active ? "activated" : "deactivated") + " for everyone."));
            }
            case "resetcounter" -> {
                counter = 0;
                save();
                s.sendMessage(msg("Counter reset. The next new waystone will be called Waystone 1."));
            }
            case "setcounter" -> {
                if (a.length < 3) {
                    s.sendMessage(msg(ChatColor.RED + "Usage: /waystone admin setcounter <number>"));
                    return true;
                }
                try {
                    int n = Integer.parseInt(a[2]);
                    if (n < 1) throw new NumberFormatException();
                    counter = n - 1;
                    save();
                    s.sendMessage(msg("The next new waystone will be called Waystone " + n + "."));
                } catch (NumberFormatException ex) {
                    s.sendMessage(msg(ChatColor.RED + "Give a whole number of 1 or more."));
                }
            }
            case "forget" -> {
                if (a.length < 3) {
                    s.sendMessage(msg(ChatColor.RED + "Usage: /waystone admin forget <player> [id]"));
                    return true;
                }
                UUID id = Bukkit.getOfflinePlayer(a[2]).getUniqueId();
                if (a.length > 3) {
                    Waystone w = findWaystone(a[3]);
                    if (w == null) {
                        s.sendMessage(msg(ChatColor.RED + "No single waystone matches \"" + a[3] + "\"."));
                        return true;
                    }
                    Set<UUID> known = discovered.get(id);
                    if (known != null) known.remove(w.id);
                    Set<UUID> favs = favorites.get(id);
                    if (favs != null) favs.remove(w.id);
                    Map<UUID, Material> icons = personalIcons.get(id);
                    if (icons != null) icons.remove(w.id);
                    s.sendMessage(msg(a[2] + " no longer has " + plainName(w) + " activated."));
                } else {
                    discovered.remove(id);
                    favorites.remove(id);
                    personalIcons.remove(id);
                    s.sendMessage(msg("Reset everything " + a[2] + " discovered, favorited and customized."));
                }
                save();
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    for (Waystone w : waystones.values()) applyVisibility(online, w);
                }
            }
            case "reload" -> {
                reloadConfig();
                for (Waystone w : waystones.values()) removeHolos(w); // respawned with the new settings
                s.sendMessage(msg("Config reloaded."));
            }
            default -> adminHelp(s);
        }
        return true;
    }

    // ====================================================================
    // Persistence
    // ====================================================================
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("counter", counter);
        for (Waystone w : waystones.values()) {
            ConfigurationSection s = y.createSection("waystones." + w.id);
            writeFields(s, w);
            s.set("world", w.base.getWorld().getName());
            s.set("x", w.base.getBlockX());
            s.set("y", w.base.getBlockY());
            s.set("z", w.base.getBlockZ());
        }
        for (Map.Entry<UUID, Set<UUID>> en : discovered.entrySet()) {
            y.set("discovered." + en.getKey(),
                    en.getValue().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        for (Map.Entry<UUID, Set<UUID>> en : favorites.entrySet()) {
            y.set("favorites." + en.getKey(),
                    en.getValue().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        for (Map.Entry<UUID, Map<UUID, Material>> en : personalIcons.entrySet()) {
            for (Map.Entry<UUID, Material> ic : en.getValue().entrySet()) {
                y.set("icons." + en.getKey() + "." + ic.getKey(), ic.getValue().name());
            }
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
                Waystone w = new Waystone(UUID.fromString(id), s.getString("name", "Waystone"),
                        UUID.fromString(s.getString("owner", "")), s.getString("ownerName", "?"), base);
                readFields(s, w);
                index(w);
            }
        }
        loadSets(y.getConfigurationSection("discovered"), discovered);
        loadSets(y.getConfigurationSection("favorites"), favorites);
        ConfigurationSection icons = y.getConfigurationSection("icons");
        if (icons != null) {
            for (String pid : icons.getKeys(false)) {
                ConfigurationSection ps = icons.getConfigurationSection(pid);
                if (ps == null) continue;
                Map<UUID, Material> map = new HashMap<>();
                for (String wid : ps.getKeys(false)) {
                    Material m = Material.matchMaterial(ps.getString(wid, ""));
                    if (m == null) continue;
                    try {
                        map.put(UUID.fromString(wid), m);
                    } catch (IllegalArgumentException ignored) { }
                }
                try {
                    personalIcons.put(UUID.fromString(pid), map);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        getLogger().info("Loaded " + waystones.size() + " waystone(s).");
    }

    private void loadSets(ConfigurationSection sec, Map<UUID, Set<UUID>> target) {
        if (sec == null) return;
        for (String pid : sec.getKeys(false)) {
            Set<UUID> set = new HashSet<>();
            for (String wid : sec.getStringList(pid)) set.add(UUID.fromString(wid));
            target.put(UUID.fromString(pid), set);
        }
    }
}
