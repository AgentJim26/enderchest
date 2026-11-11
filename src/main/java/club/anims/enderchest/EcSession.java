package club.anims.enderchest;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EcSession {
    public static final int SLOTS_PER_PAGE = 36;
    public static final int MAX_PAGES = 17;

    public final UUID uuid;
    public int pages = 1;
    public SortMode sortMode = SortMode.CUSTOM;
    public int page = 1;
    public String search = null;
    /** global index = (page - 1) * SLOTS_PER_PAGE + slot */
    public final Map<Integer, ItemStack> items = new HashMap<>();

    public EcSession(UUID uuid) {
        this.uuid = uuid;
    }

    public int capacity() {
        return pages * SLOTS_PER_PAGE;
    }
}
