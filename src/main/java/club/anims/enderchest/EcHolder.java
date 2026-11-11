package club.anims.enderchest;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EcHolder implements InventoryHolder {
    public enum Type { MAIN, UPGRADE }

    private final EnderChestPlugin plugin;
    private final UUID uuid;
    private final Type type;
    private Inventory inventory;
    private int mainPage = 1;
    private boolean handled = false;
    private final Map<Integer, Integer> searchSlots = new HashMap<>();

    public EcHolder(EnderChestPlugin plugin, UUID uuid, Type type) {
        this.plugin = plugin;
        this.uuid = uuid;
        this.type = type;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public EnderChestPlugin getPlugin() {
        return plugin;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Type getType() {
        return type;
    }

    public int getMainPage() {
        return mainPage;
    }

    public void setMainPage(int mainPage) {
        this.mainPage = mainPage;
    }

    public boolean isHandled() {
        return handled;
    }

    public void setHandled(boolean handled) {
        this.handled = handled;
    }

    public Map<Integer, Integer> getSearchSlots() {
        return searchSlots;
    }
}
