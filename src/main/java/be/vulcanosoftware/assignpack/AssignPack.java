package be.vulcanosoftware.assignpack;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.net.URI;
import java.util.*;

public class AssignPack extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    public static class PackEntry {
        private final UUID uuid;
        private final String url;
        private final String hash;

        public PackEntry(UUID uuid, String url, String hash) {
            this.uuid = uuid;
            this.url = url;
            this.hash = hash != null ? hash : "";
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getUrl() {
            return url;
        }

        public String getHash() {
            return hash;
        }
    }

    private FileConfiguration config;
    // Track pending pack UUIDs per player UUID
    private final Map<UUID, Set<UUID>> pendingPacks = new HashMap<>();
    // Track active resource packs sent/loaded for player
    private final Map<UUID, List<PackEntry>> activePacks = new HashMap<>();

    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final Map<UUID, Long> protectionEndTimes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        if (getCommand("assignpack") != null) {
            getCommand("assignpack").setExecutor(this);
            getCommand("assignpack").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("Speler niet gevonden.");
            return true;
        }

        String arg1 = args[1].toLowerCase();

        if (arg1.equals("clear")) {
            return handleClear(sender, target);
        } else if (arg1.equals("list")) {
            return handleList(sender, target);
        } else if (arg1.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> remove <uuid|index|url>");
                return true;
            }
            return handleRemove(sender, target, args[2]);
        } else if (arg1.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> add <url> [hash] [uuid] [required]");
                return true;
            }
            String url = args[2];
            String hash = args.length > 3 ? args[3] : null;
            String uuidStr = args.length > 4 ? args[4] : null;
            boolean required = args.length > 5 && Boolean.parseBoolean(args[5]);
            return handleSendPack(sender, target, url, hash, uuidStr, required, false);
        } else if (arg1.equals("set")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> set <url> [hash] [uuid] [required]");
                return true;
            }
            String url = args[2];
            String hash = args.length > 3 ? args[3] : null;
            String uuidStr = args.length > 4 ? args[4] : null;
            boolean required = args.length > 5 && Boolean.parseBoolean(args[5]);
            return handleSendPack(sender, target, url, hash, uuidStr, required, true);
        } else {
            // Legacy syntax: /assignpack <speler> <url> [hash] [required]
            String url = args[1];
            String hash = args.length > 2 ? args[2] : null;
            boolean required = args.length > 3 && Boolean.parseBoolean(args[3]);
            boolean defaultStacking = config.getBoolean("default-stacking", false);
            boolean replace = !defaultStacking;
            return handleSendPack(sender, target, url, hash, null, required, replace);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("Gebruik:");
        sender.sendMessage("/assignpack <speler> <url> [hash] [required]");
        sender.sendMessage("/assignpack <speler> add <url> [hash] [uuid] [required]");
        sender.sendMessage("/assignpack <speler> set <url> [hash] [uuid] [required]");
        sender.sendMessage("/assignpack <speler> remove <uuid|index|url>");
        sender.sendMessage("/assignpack <speler> clear");
        sender.sendMessage("/assignpack <speler> list");
    }

    private boolean handleClear(CommandSender sender, Player target) {
        target.clearResourcePacks();
        activePacks.remove(target.getUniqueId());
        pendingPacks.remove(target.getUniqueId());
        sender.sendMessage("Alle resourcepacks zijn verwijderd voor " + target.getName());
        return true;
    }

    private boolean handleList(CommandSender sender, Player target) {
        List<PackEntry> packs = activePacks.get(target.getUniqueId());
        if (packs == null || packs.isEmpty()) {
            sender.sendMessage(target.getName() + " heeft op dit moment geen actieve resourcepacks.");
            return true;
        }

        sender.sendMessage("Actieve resourcepacks van " + target.getName() + " (" + packs.size() + "):");
        for (int i = 0; i < packs.size(); i++) {
            PackEntry pack = packs.get(i);
            sender.sendMessage((i + 1) + ". UUID: " + pack.getUuid() + " | URL: " + pack.getUrl());
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, Player target, String identifier) {
        List<PackEntry> packs = activePacks.get(target.getUniqueId());
        if (packs == null || packs.isEmpty()) {
            sender.sendMessage(target.getName() + " heeft geen actieve resourcepacks.");
            return true;
        }

        PackEntry packToRemove = null;

        // 1. Try index
        try {
            int index = Integer.parseInt(identifier) - 1;
            if (index >= 0 && index < packs.size()) {
                packToRemove = packs.get(index);
            }
        } catch (NumberFormatException ignored) {}

        // 2. Try UUID
        if (packToRemove == null) {
            try {
                UUID uuid = UUID.fromString(identifier);
                for (PackEntry p : packs) {
                    if (p.getUuid().equals(uuid)) {
                        packToRemove = p;
                        break;
                    }
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // 3. Try URL exact match
        if (packToRemove == null) {
            for (PackEntry p : packs) {
                if (p.getUrl().equalsIgnoreCase(identifier)) {
                    packToRemove = p;
                    break;
                }
            }
        }

        if (packToRemove == null) {
            sender.sendMessage("Resourcepack '" + identifier + "' niet gevonden voor " + target.getName());
            return true;
        }

        target.removeResourcePacks(packToRemove.getUuid());
        packs.remove(packToRemove);
        sender.sendMessage("Resourcepack " + packToRemove.getUuid() + " verwijderd voor " + target.getName());
        return true;
    }

    private boolean handleSendPack(CommandSender sender, Player target, String url, String hash, String uuidStr, boolean required, boolean replace) {
        UUID packUuid;
        if (uuidStr != null && !uuidStr.isEmpty()) {
            try {
                packUuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                sender.sendMessage("Ongeldige UUID indeling.");
                return true;
            }
        } else {
            packUuid = UUID.nameUUIDFromBytes((target.getUniqueId().toString() + url).getBytes());
        }

        try {
            URI uri = URI.create(url);
            ResourcePackInfo.Builder infoBuilder = ResourcePackInfo.resourcePackInfo()
                    .id(packUuid)
                    .uri(uri);

            if (hash != null && !hash.isEmpty()) {
                infoBuilder.hash(hash);
            }

            ResourcePackInfo packInfo = infoBuilder.build();
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(packInfo)
                    .replace(replace)
                    .required(required)
                    .build();

            // Track state
            UUID playerUuid = target.getUniqueId();
            pendingPacks.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(packUuid);

            if (replace) {
                List<PackEntry> list = new ArrayList<>();
                list.add(new PackEntry(packUuid, url, hash));
                activePacks.put(playerUuid, list);
            } else {
                List<PackEntry> list = activePacks.computeIfAbsent(playerUuid, k -> new ArrayList<>());
                list.removeIf(p -> p.getUuid().equals(packUuid));
                list.add(new PackEntry(packUuid, url, hash));
            }

            applyRestrictions(target);

            target.sendResourcePacks(request);
            sender.sendMessage("Resourcepack verstuurd naar " + target.getName() + " (replace=" + replace + ")");
        } catch (Exception e) {
            sender.sendMessage("Fout bij versturen resourcepack: " + e.getMessage());
        }

        return true;
    }

    private void applyRestrictions(Player target) {
        UUID uuid = target.getUniqueId();

        // Save inventory if not already saved during this restriction window
        if (config.getBoolean("restrictions.inventory-access") && !savedInventories.containsKey(uuid)) {
            savedInventories.put(uuid, target.getInventory().getContents());
            target.getInventory().clear();
        }

        if (config.getBoolean("restrictions.blindness")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false));
        }
        if (config.getBoolean("restrictions.jumping")) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, 128, false, false));
        }
        if (config.getBoolean("restrictions.movement")) {
            target.setWalkSpeed(0.0f);
        }
        if (config.getBoolean("restrictions.flying")) {
            target.setFlySpeed(0.0f);
            target.setAllowFlight(false);
        }
    }

    private void clearRestrictions(Player player) {
        UUID uuid = player.getUniqueId();

        if (config.getBoolean("restrictions.inventory-access")) {
            ItemStack[] savedItems = savedInventories.remove(uuid);
            if (savedItems != null) {
                player.getInventory().setContents(savedItems);
                getLogger().info("Inventaris van " + player.getName() + " hersteld.");
            }
        }

        if (config.getBoolean("restrictions.blindness")) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
        if (config.getBoolean("restrictions.jumping")) {
            player.removePotionEffect(PotionEffectType.JUMP);
        }
        if (config.getBoolean("restrictions.movement")) {
            player.setWalkSpeed(0.2f);
        }
        if (config.getBoolean("restrictions.flying")) {
            player.setFlySpeed(0.1f);
            player.setAllowFlight(player.hasPermission("minecraft.command.fly"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            String partial = args[1].toLowerCase();
            List<String> subcommands = Arrays.asList("add", "set", "clear", "remove", "list");
            for (String sub : subcommands) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 3) {
            String sub = args[1].toLowerCase();
            if (sub.equals("remove")) {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target != null) {
                    List<PackEntry> packs = activePacks.get(target.getUniqueId());
                    if (packs != null) {
                        String partial = args[2].toLowerCase();
                        for (int i = 0; i < packs.size(); i++) {
                            String idx = String.valueOf(i + 1);
                            if (idx.startsWith(partial)) {
                                completions.add(idx);
                            }
                            String uuid = packs.get(i).getUuid().toString();
                            if (uuid.toLowerCase().startsWith(partial)) {
                                completions.add(uuid);
                            }
                        }
                    }
                }
            }
        } else if (args.length == 6) {
            String sub = args[1].toLowerCase();
            if (sub.equals("add") || sub.equals("set")) {
                completions.add("true");
                completions.add("false");
            }
        }

        return completions;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (pendingPacks.containsKey(player.getUniqueId()) && config.getBoolean("restrictions.inventory-access")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (pendingPacks.containsKey(player.getUniqueId()) && config.getBoolean("restrictions.block-break")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (pendingPacks.containsKey(player.getUniqueId()) && config.getBoolean("restrictions.block-place")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID uuid = player.getUniqueId();
            Long protectionEnd = protectionEndTimes.get(uuid);
            boolean hasPendingPack = pendingPacks.containsKey(uuid) && !pendingPacks.get(uuid).isEmpty();
            boolean damageProtectionEnabled = config.getBoolean("restrictions.damage-protection");
            long now = System.currentTimeMillis();

            if ((hasPendingPack && damageProtectionEnabled) ||
                (protectionEnd != null && now < protectionEnd)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            UUID uuid = damager.getUniqueId();
            boolean hasPendingPack = pendingPacks.containsKey(uuid) && !pendingPacks.get(uuid).isEmpty();
            if (hasPendingPack && config.getBoolean("restrictions.damage-protection")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!pendingPacks.containsKey(uuid) || pendingPacks.get(uuid).isEmpty()) {
            return;
        }

        boolean restrictJumping = config.getBoolean("restrictions.jumping");
        boolean restrictCrouching = config.getBoolean("restrictions.crouching");
        boolean restrictMovement = config.getBoolean("restrictions.movement");

        if (restrictJumping && event.getTo().getY() > event.getFrom().getY()) {
            event.setCancelled(true);
            event.setTo(event.getFrom());
            return;
        }

        if (restrictCrouching && player.isSneaking()) {
            event.setCancelled(true);
            event.setTo(event.getFrom());
            return;
        }

        if (restrictMovement) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Set<UUID> pending = pendingPacks.get(playerUuid);

        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {

            UUID packId = event.getID();
            if (pending != null && packId != null) {
                pending.remove(packId);
            }

            boolean finished = (pending == null || pending.isEmpty());

            if (finished) {
                clearRestrictions(player);
                pendingPacks.remove(playerUuid);

                int protectionSeconds = config.getInt("post-download-protection-seconds", 0);
                if (protectionSeconds > 0 && event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                    protectionEndTimes.put(playerUuid, System.currentTimeMillis() + (protectionSeconds * 1000L));

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            protectionEndTimes.remove(playerUuid);
                        }
                    }.runTaskLater(this, protectionSeconds * 20L);
                }
            }

            if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
                if (config.getBoolean("kick-on-decline")) {
                    player.kickPlayer(config.getString("kick-message-decline"));
                }
            } else if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
                if (config.getBoolean("kick-on-failure")) {
                    player.kickPlayer(config.getString("kick-message-failure"));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (savedInventories.containsKey(uuid)) {
            ItemStack[] savedItems = savedInventories.remove(uuid);
            player.getInventory().setContents(savedItems);
            getLogger().info("Inventaris van " + player.getName() + " hersteld na disconnectie.");
        }
        pendingPacks.remove(uuid);
        activePacks.remove(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        player.setWalkSpeed(0.2f);

        if (savedInventories.containsKey(uuid)) {
            ItemStack[] savedItems = savedInventories.remove(uuid);
            if (savedItems != null) {
                player.getInventory().setContents(savedItems);
                getLogger().info("Inventaris van " + player.getName() + " hersteld na herverbinding.");
            }
        }

        pendingPacks.remove(uuid);
        activePacks.remove(uuid);

        player.removePotionEffect(PotionEffectType.JUMP);
        player.setVelocity(new Vector(0, 0, 0));
    }
}
