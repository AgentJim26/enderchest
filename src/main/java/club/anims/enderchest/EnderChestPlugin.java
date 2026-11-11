package club.anims.enderchest;

import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class EnderChestPlugin extends JavaPlugin implements Listener {

    private static final int[] STORAGE_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25,
            26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    private static final int[] UI_SLOTS = {
            0, 1, 2, 4, 6, 7, 8, 46, 47, 48, 50, 51, 52
    };
    private static final int[] UPGRADE_UI_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            12, 13, 14,
            17, 18, 19, 20, 21,
            23, 24, 25, 26
    };

    private static final int SLOT_PREVIOUS = 3;
    private static final int SLOT_NEXT = 5;
    private static final int SLOT_SORT = 45;
    private static final int SLOT_SEARCH = 49;
    private static final int SLOT_UPGRADE = 53;

    private static final int UPGRADE_INPUT = 11;
    private static final int UPGRADE_ACCEPT = 15;
    private static final int UPGRADE_CANCEL = 16;
    private static final int UPGRADE_STATUS = 22;

    private final Map<UUID, EcSession> sessions = new HashMap<>();
    private final Map<UUID, BlockPosition> pendingSigns = new HashMap<>();
    private final Set<UUID> resortQueued = new HashSet<>();
    private EcStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            store = new EcStore(getDataFolder());
        } catch (Exception ex) {
            getLogger().severe("Could not open enderchest.db: " + ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("ec") != null) {
            getCommand("ec").setExecutor(this);
        }
        getLogger().info("EnderChest enabled with SQLite storage.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof EcHolder holder
                    && holder.getPlugin() == this) {
                player.closeInventory();
            }
        }
        if (store != null) {
            for (EcSession session : new ArrayList<>(sessions.values())) {
                save(session);
            }
            store.close();
            store = null;
        }
        sessions.clear();
        pendingSigns.clear();
        resortQueued.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!getConfig().getBoolean("command-enabled", false)) {
            sender.sendMessage("The /ec command is disabled. Right-click an ender chest instead.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open an ender chest.");
            return true;
        }
        if (!player.hasPermission("enderchest.use")) {
            player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        openMain(player, session(player), 1, null);
        return true;
    }

    private EcSession session(Player player) {
        UUID id = player.getUniqueId();
        EcSession cached = sessions.get(id);
        if (cached != null) return cached;

        EcSession session = null;
        try {
            session = store.load(id);
        } catch (SQLException ex) {
            getLogger().severe("Failed to load ender chest for " + player.getName() + ": " + ex.getMessage());
        }
        if (session == null) {
            session = new EcSession(id);
            ItemStack[] vanilla = player.getEnderChest().getContents();
            for (int i = 0; i < Math.min(27, vanilla.length); i++) {
                ItemStack stack = vanilla[i];
                if (stack != null && !stack.getType().isAir()) {
                    session.items.put(i, stack.clone());
                }
            }
            save(session);
        }
        sessions.put(id, session);
        return session;
    }

    private void openMain(Player player, EcSession session, int page, String search) {
        session.search = (search == null || search.isBlank()) ? null : search.trim();
        session.page = clampPage(session, page);
        EcHolder holder = new EcHolder(this, session.uuid, EcHolder.Type.MAIN);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Ender Chest: Page " + session.page + " of " + maxPage(session)));
        holder.setInventory(inventory);
        renderMain(inventory, session, holder);
        player.openInventory(inventory);
    }

    private void renderMain(Inventory inventory, EcSession session, EcHolder holder) {
        inventory.clear();
        holder.getSearchSlots().clear();
        ItemStack pane = pane();
        for (int slot : UI_SLOTS) {
            inventory.setItem(slot, pane.clone());
        }
        inventory.setItem(SLOT_PREVIOUS, button(Material.ARROW, "Previous Page"));
        inventory.setItem(SLOT_NEXT, button(Material.ARROW, "Next Page"));
        inventory.setItem(SLOT_SORT, button(Material.HOPPER, "Sort (" + session.sortMode.pretty() + ")"));
        ItemStack search = button(Material.OAK_SIGN, "Search");
        if (session.search != null) {
            setLore(search, List.of(Component.text("Query: " + session.search, NamedTextColor.GRAY)));
        }
        inventory.setItem(SLOT_SEARCH, search);
        ItemStack upgrade = button(Material.OMINOUS_TRIAL_KEY, "Upgrade");
        setLore(upgrade, List.of(
                Component.text("Requires Eyes of Ender", NamedTextColor.GRAY),
                Component.text("1 eye = 1 page", NamedTextColor.DARK_GRAY),
                Component.text("Max 17 pages", NamedTextColor.DARK_GRAY)
        ));
        ItemMeta upgradeMeta = upgrade.getItemMeta();
        upgradeMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        upgrade.setItemMeta(upgradeMeta);
        inventory.setItem(SLOT_UPGRADE, upgrade);

        if (session.search != null) {
            List<SearchEntry> entries = searchEntries(session);
            int start = (session.page - 1) * EcSession.SLOTS_PER_PAGE;
            for (int i = 0; i < EcSession.SLOTS_PER_PAGE && start + i < entries.size(); i++) {
                SearchEntry entry = entries.get(start + i);
                inventory.setItem(STORAGE_SLOTS[i], entry.stack().clone());
                holder.getSearchSlots().put(STORAGE_SLOTS[i], entry.index());
            }
        } else {
            for (int i = 0; i < EcSession.SLOTS_PER_PAGE; i++) {
                ItemStack stack = session.items.get((session.page - 1) * EcSession.SLOTS_PER_PAGE + i);
                if (stack != null) {
                    inventory.setItem(STORAGE_SLOTS[i], stack.clone());
                }
            }
        }
    }

    private void renderUpgrade(Inventory inventory, EcSession session) {
        inventory.clear();
        ItemStack pane = pane();
        for (int slot : UPGRADE_UI_SLOTS) {
            inventory.setItem(slot, pane.clone());
        }
        inventory.setItem(UPGRADE_ACCEPT, button(Material.LIME_STAINED_GLASS_PANE, "Accept"));
        inventory.setItem(UPGRADE_CANCEL, button(Material.RED_STAINED_GLASS_PANE, "Cancel"));
        inventory.setItem(UPGRADE_STATUS, statusItem(session, null));
    }

    private ItemStack pane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("UI", NamedTextColor.GRAY));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void setLore(ItemStack item, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private ItemStack statusItem(EcSession session, ItemStack input) {
        int current = session.pages;
        int count = input != null && isUpgradeItem(input) ? input.getAmount() : 0;
        int addable = Math.min(count, EcSession.MAX_PAGES - current);
        String status;
        if (count == 0) {
            status = "Status: Unchanged";
        } else if (addable <= 0) {
            status = "Status: Cannot add more pages";
        } else {
            status = "Status: Will add " + addable + " page" + (addable == 1 ? "" : "s");
        }
        ItemStack item = button(Material.WRITABLE_BOOK, "Status");
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(status));
        lore.add(Component.text("Current Pages: " + current));
        if (count > 0 && addable > 0) {
            lore.add(Component.text("After: " + (current + addable) + " / " + EcSession.MAX_PAGES));
        } else if (count > 0) {
            lore.add(Component.text("Cap: " + EcSession.MAX_PAGES + " pages"));
        }
        lore.add(Component.text("Place Eyes of Ender in the empty slot", NamedTextColor.DARK_GRAY));
        setLore(item, lore);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEnderChestOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!player.hasPermission("enderchest.use")) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> openMain(player, session(player), 1, null));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EcHolder holder) || holder.getPlugin() != this) return;
        EcSession session = sessions.get(holder.getUuid());
        if (session == null) return;

        boolean inTop = event.getClickedInventory() == top;
        int raw = event.getRawSlot();

        if (holder.getType() == EcHolder.Type.UPGRADE) {
            handleUpgradeClick(event, player, top, holder, session, raw, inTop);
            return;
        }

        if (inTop) {
            if (isUiSlot(raw)) {
                event.setCancelled(true);
                return;
            }
            if (raw == SLOT_PREVIOUS) {
                event.setCancelled(true);
                changePage(player, top, holder, session, -1);
                return;
            }
            if (raw == SLOT_NEXT) {
                event.setCancelled(true);
                changePage(player, top, holder, session, 1);
                return;
            }
            if (raw == SLOT_SORT) {
                event.setCancelled(true);
                cycleSort(top, holder, session);
                return;
            }
            if (raw == SLOT_SEARCH) {
                event.setCancelled(true);
                openSearch(player, top, session);
                return;
            }
            if (raw == SLOT_UPGRADE) {
                event.setCancelled(true);
                openUpgrade(player, top, session);
                return;
            }
            if (isStorageSlot(raw)) {
                if (session.search != null) {
                    handleSearchSlot(event, player, top, holder, session, raw);
                    return;
                }
                if (session.sortMode != SortMode.CUSTOM) {
                    scheduleSortRefresh(player, top, holder, session);
                }
                return;
            }
            event.setCancelled(true);
        } else {
            if (session.search != null) {
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                    if (session.sortMode != SortMode.CUSTOM) {
                        ItemStack current = event.getCurrentItem();
                        if (current != null && !current.getType().isAir() && insertIntoEc(session, current)) {
                            event.setCurrentItem(null);
                            refreshSearch(player, top, holder, session);
                        }
                    }
                }
                return;
            }
            if (session.sortMode != SortMode.CUSTOM) {
                scheduleSortRefresh(player, top, holder, session);
            }
        }
    }

    private void handleSearchSlot(InventoryClickEvent event, Player player, Inventory top, EcHolder holder,
                                  EcSession session, int raw) {
        event.setCancelled(true);
        Integer index = holder.getSearchSlots().get(raw);
        ItemStack cursor = event.getCursor();
        boolean emptyCursor = cursor == null || cursor.getType().isAir();

        if (event.isShiftClick()) {
            if (index == null) return;
            ItemStack stack = session.items.remove(index);
            if (stack != null) {
                giveOrDrop(player, stack);
            }
            refreshSearch(player, top, holder, session);
            return;
        }

        if (emptyCursor) {
            if (index == null) return;
            ItemStack stack = session.items.remove(index);
            if (stack != null) {
                player.setItemOnCursor(stack);
                refreshSearch(player, top, holder, session);
            }
            return;
        }

        if (session.sortMode == SortMode.CUSTOM) {
            return;
        }

        // Placing an item: use the mapped result slot if it is free, otherwise the first free EC slot.
        if (index != null) {
            ItemStack existing = session.items.get(index);
            if (existing == null || existing.getType().isAir()) {
                session.items.put(index, cursor.clone());
                player.setItemOnCursor(null);
                refreshSearch(player, top, holder, session);
                return;
            }
        }
        if (insertIntoEc(session, cursor)) {
            player.setItemOnCursor(null);
            refreshSearch(player, top, holder, session);
        }
    }

    private void refreshSearch(Player player, Inventory top, EcHolder holder, EcSession session) {
        session.page = clampPage(session, session.page);
        renderMain(top, session, holder);
        updateTitle(player, session);
        save(session);
    }

    private void handleUpgradeClick(InventoryClickEvent event, Player player, Inventory top, EcHolder holder,
                                    EcSession session, int raw, boolean inTop) {
        if (!inTop) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }
            scheduleUpgradeValidation(player, top, holder, session);
            return;
        }
        if (raw == UPGRADE_ACCEPT) {
            event.setCancelled(true);
            acceptUpgrade(player, top, holder, session);
            return;
        }
        if (raw == UPGRADE_CANCEL) {
            event.setCancelled(true);
            cancelUpgrade(player, top, holder, session);
            return;
        }
        if (raw == UPGRADE_INPUT) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !isUpgradeItem(cursor)) {
                event.setCancelled(true);
                return;
            }
            scheduleUpgradeValidation(player, top, holder, session);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EcHolder holder) || holder.getPlugin() != this) return;
        EcSession session = sessions.get(holder.getUuid());
        if (session == null) return;
        if (holder.getType() == EcHolder.Type.UPGRADE) {
            event.setCancelled(true);
            return;
        }
        if (session.search != null) {
            event.setCancelled(true);
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && !isStorageSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        if (session.sortMode != SortMode.CUSTOM) {
            scheduleSortRefresh(player, top, holder, session);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EcHolder holder) || holder.getPlugin() != this) return;
        EcSession session = sessions.get(holder.getUuid());
        if (session == null) return;

        if (holder.getType() == EcHolder.Type.MAIN) {
            if (session.search == null) {
                capturePage(top, session);
                if (session.sortMode != SortMode.CUSTOM) {
                    sortAll(session);
                }
                save(session);
            } else {
                save(session);
            }
        } else if (!holder.isHandled()) {
            ItemStack input = top.getItem(UPGRADE_INPUT);
            if (input != null) {
                top.setItem(UPGRADE_INPUT, null);
                giveOrDrop(player, input);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingSigns.remove(id);
        resortQueued.remove(id);
        EcSession session = sessions.remove(id);
        if (session != null) {
            save(session);
        }
    }

    @EventHandler
    public void onUncheckedSignChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        BlockPosition pending = pendingSigns.get(player.getUniqueId());
        if (pending == null || !pending.equals(event.getEditedBlockPosition())) return;
        pendingSigns.remove(player.getUniqueId());

        List<String> lines = event.lines().stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .toList();
        String first = lines.isEmpty() ? "" : lines.get(0);
        if (first.regionMatches(true, 0, "Search:", 0, 7)) {
            first = first.substring(7);
        }
        String raw = (first + " " + (lines.size() <= 1 ? "" : String.join(" ", lines.subList(1, lines.size())))).trim();
        String query = raw.isEmpty() ? null : raw;

        Location location = pending.toLocation(player.getWorld());
        player.sendBlockChange(location, location.getBlock().getBlockData());

        EcSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        Bukkit.getScheduler().runTask(this, () -> openMain(player, session, 1, query));
    }

    private void changePage(Player player, Inventory top, EcHolder holder, EcSession session, int delta) {
        int target = Math.max(1, Math.min(maxPage(session), session.page + delta));
        if (target == session.page) return;
        capturePage(top, session);
        session.page = target;
        renderMain(top, session, holder);
        updateTitle(player, session);
    }

    private void updateTitle(Player player, EcSession session) {
        try {
            player.getOpenInventory().setTitle("Ender Chest: Page " + session.page + " of " + maxPage(session));
        } catch (RuntimeException ex) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof EcHolder holder && holder.getPlugin() == this) {
                player.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openMain(player, session, session.page, session.search));
            }
        }
    }

    private void cycleSort(Inventory top, EcHolder holder, EcSession session) {
        if (session.search == null) {
            capturePage(top, session);
        }
        session.sortMode = session.sortMode.next();
        if (session.sortMode != SortMode.CUSTOM) {
            sortAll(session);
        }
        save(session);
        renderMain(top, session, holder);
    }

    private void scheduleSortRefresh(Player player, Inventory top, EcHolder holder, EcSession session) {
        if (!resortQueued.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this, () -> {
            resortQueued.remove(player.getUniqueId());
            if (player.getOpenInventory().getTopInventory() != top) return;
            if (session.search != null) return;
            capturePage(top, session);
            sortAll(session);
            save(session);
            renderMain(top, session, holder);
        });
    }

    private void openSearch(Player player, Inventory top, EcSession session) {
        if (session.search == null) {
            capturePage(top, session);
        }
        save(session);
        String current = session.search == null ? "" : session.search;
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            Location location = player.getLocation().add(0, 2, 0);
            BlockPosition position = Position.block(location);
            pendingSigns.put(player.getUniqueId(), position);
            BlockData data = Material.OAK_SIGN.createBlockData();
            BlockState state = data.createBlockState();
            if (state instanceof Sign sign) {
                sign.line(0, Component.text("Search:"));
                sign.line(1, Component.text(current));
                sign.setEditable(true);
                player.sendBlockChange(location, data);
                try {
                    player.sendBlockUpdate(location, sign);
                } catch (RuntimeException ex) {
                    getLogger().warning("Could not prefill search sign: " + ex.getMessage());
                }
            }
            player.openVirtualSign(position, Side.FRONT);
        });
    }

    private void openUpgrade(Player player, Inventory top, EcSession session) {
        if (session.search == null) {
            capturePage(top, session);
        }
        save(session);
        int returnPage = session.page;
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            EcHolder holder = new EcHolder(this, session.uuid, EcHolder.Type.UPGRADE);
            holder.setMainPage(returnPage);
            Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Upgrade Storage"));
            holder.setInventory(inventory);
            renderUpgrade(inventory, session);
            player.openInventory(inventory);
        });
    }

    private void acceptUpgrade(Player player, Inventory top, EcHolder holder, EcSession session) {
        ItemStack input = top.getItem(UPGRADE_INPUT);
        if (input != null && isUpgradeItem(input)) {
            int addable = Math.min(input.getAmount(), EcSession.MAX_PAGES - session.pages);
            if (addable > 0) {
                session.pages += addable;
                int remaining = input.getAmount() - addable;
                top.setItem(UPGRADE_INPUT, null);
                if (remaining > 0) {
                    giveOrDrop(player, new ItemStack(Material.ENDER_EYE, remaining));
                }
                save(session);
            } else {
                top.setItem(UPGRADE_INPUT, null);
                giveOrDrop(player, input);
            }
        }
        holder.setHandled(true);
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> openMain(player, session, holder.getMainPage(), null));
    }

    private void cancelUpgrade(Player player, Inventory top, EcHolder holder, EcSession session) {
        ItemStack input = top.getItem(UPGRADE_INPUT);
        if (input != null) {
            top.setItem(UPGRADE_INPUT, null);
            giveOrDrop(player, input);
        }
        holder.setHandled(true);
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> openMain(player, session, holder.getMainPage(), null));
    }

    private void scheduleUpgradeValidation(Player player, Inventory top, EcHolder holder, EcSession session) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.getOpenInventory().getTopInventory() != top) return;
            if (!(top.getHolder() instanceof EcHolder current) || current != holder) return;
            ItemStack input = top.getItem(UPGRADE_INPUT);
            if (input != null && !isUpgradeItem(input)) {
                top.setItem(UPGRADE_INPUT, null);
                giveOrDrop(player, input);
                input = null;
            }
            top.setItem(UPGRADE_STATUS, statusItem(session, input));
        });
    }

    private void capturePage(Inventory inventory, EcSession session) {
        if (session.search != null) return;
        int base = (session.page - 1) * EcSession.SLOTS_PER_PAGE;
        for (int i = 0; i < EcSession.SLOTS_PER_PAGE; i++) {
            ItemStack stack = inventory.getItem(STORAGE_SLOTS[i]);
            if (stack == null || stack.getType().isAir()) {
                session.items.remove(base + i);
            } else {
                session.items.put(base + i, stack.clone());
            }
        }
    }

    private void sortAll(EcSession session) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int index : new TreeSet<>(session.items.keySet())) {
            ItemStack stack = session.items.get(index);
            if (stack != null && !stack.getType().isAir()) {
                stacks.add(stack.clone());
            }
        }
        stacks = merge(stacks);
        Comparator<ItemStack> comparator = Comparator
                .comparing(this::sortKey, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(stack -> stack.getType().name());
        if (session.sortMode == SortMode.DESCENDING) {
            comparator = comparator.reversed();
        }
        stacks.sort(comparator);
        session.items.clear();
        int index = 0;
        for (ItemStack stack : stacks) {
            session.items.put(index++, stack);
        }
    }

    private List<ItemStack> merge(List<ItemStack> input) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : input) {
            int remaining = stack.getAmount();
            for (ItemStack existing : result) {
                if (remaining <= 0) break;
                if (!existing.isSimilar(stack)) continue;
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) continue;
                int move = Math.min(space, remaining);
                existing.setAmount(existing.getAmount() + move);
                remaining -= move;
            }
            while (remaining > 0) {
                ItemStack copy = stack.clone();
                int amount = Math.min(remaining, copy.getMaxStackSize());
                copy.setAmount(amount);
                result.add(copy);
                remaining -= amount;
            }
        }
        return result;
    }

    private String sortKey(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private record SearchEntry(int index, ItemStack stack) {
    }

    private List<SearchEntry> searchEntries(EcSession session) {
        if (session.search == null) return List.of();
        String needle = session.search.toLowerCase(Locale.ROOT);
        List<SearchEntry> entries = new ArrayList<>();
        for (int index : new TreeSet<>(session.items.keySet())) {
            ItemStack stack = session.items.get(index);
            if (stack == null) continue;
            String haystack = (sortKey(stack) + " " + stack.getType().name()).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                entries.add(new SearchEntry(index, stack));
            }
        }
        return entries;
    }

    private int maxPage(EcSession session) {
        if (session.search != null) {
            return Math.max(1, (searchEntries(session).size() + EcSession.SLOTS_PER_PAGE - 1)
                    / EcSession.SLOTS_PER_PAGE);
        }
        return Math.max(1, session.pages);
    }

    private int clampPage(EcSession session, int page) {
        return Math.max(1, Math.min(maxPage(session), page));
    }

    private boolean isUpgradeItem(ItemStack stack) {
        return stack.getType() == Material.ENDER_EYE;
    }

    private boolean isUiSlot(int raw) {
        for (int slot : UI_SLOTS) {
            if (slot == raw) return true;
        }
        return false;
    }

    private boolean isStorageSlot(int raw) {
        for (int slot : STORAGE_SLOTS) {
            if (slot == raw) return true;
        }
        return false;
    }

    private boolean insertIntoEc(EcSession session, ItemStack stack) {
        for (int i = 0; i < session.capacity(); i++) {
            if (!session.items.containsKey(i)) {
                session.items.put(i, stack.clone());
                return true;
            }
        }
        return false;
    }

    private void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void save(EcSession session) {
        if (store == null) return;
        try {
            store.save(session);
        } catch (SQLException ex) {
            getLogger().severe("Failed to save ender chest for " + session.uuid + ": " + ex.getMessage());
        }
    }
}
