package be.vulcanosoftware.assignpack;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AssignPack extends JavaPlugin implements Listener, CommandExecutor {

    private FileConfiguration config;
    private final HashMap<UUID, String> pendingPacks = new HashMap<>();
    private final HashMap<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final HashMap<UUID, Long> protectionEndTimes = new HashMap<>();

    // Record of last assigned pack per player (or global last used)
    public static class LastPackInfo {
        private final List<String> originalUrls;
        private final String workingUrl;
        private final String rawHash;

        public LastPackInfo(List<String> originalUrls, String workingUrl, String rawHash) {
            this.originalUrls = originalUrls;
            this.workingUrl = workingUrl;
            this.rawHash = rawHash;
        }

        public List<String> getOriginalUrls() {
            return originalUrls;
        }

        public String getWorkingUrl() {
            return workingUrl;
        }

        public String getRawHash() {
            return rawHash;
        }
    }

    private final Map<UUID, LastPackInfo> lastPlayerPacks = new ConcurrentHashMap<>();
    private volatile LastPackInfo globalLastPack = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        getCommand("assignpack").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("Gebruik: /assignpack <speler|list|test> [url1] [url2] ... [sha1|auto]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("list")) {
            return handleListCommand(sender);
        }

        if (sub.equals("test")) {
            return handleTestCommand(sender, args);
        }

        // Standard usage: /assignpack <speler> <url1> [url2] ... [sha1|auto]
        if (args.length < 2) {
            sender.sendMessage("Gebruik: /assignpack <speler> <url1> [url2] ... [sha1|auto]");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("Speler niet gevonden.");
            return true;
        }

        ResourcePackUtils.ParsedArgs parsedArgs = ResourcePackUtils.parseUrlsAndHash(args, 1);
        if (parsedArgs.getUrls().isEmpty()) {
            sender.sendMessage("Geen geldige URLs opgegeven.");
            return true;
        }

        // Apply restrictions on main thread immediately
        applyRestrictions(target);

        // Perform HTTP failover and SHA-1 fetching asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            ResourcePackUtils.MirrorResult result = ResourcePackUtils.selectMirrorAndFetchHash(
                    parsedArgs.getUrls(),
                    parsedArgs.getHashArg()
            );

            Bukkit.getScheduler().runTask(this, () -> {
                // Ensure player is still online
                if (!target.isOnline()) {
                    pendingPacks.remove(target.getUniqueId());
                    savedInventories.remove(target.getUniqueId());
                    return;
                }

                if (!result.isSuccess()) {
                    sender.sendMessage("Fout bij het toewijzen van resourcepack: " + result.getError());
                    removeRestrictions(target, false);
                    pendingPacks.remove(target.getUniqueId());
                    return;
                }

                String workingUrl = result.getWorkingUrl();
                byte[] hashBytes = result.getHashBytes();
                String rawHash = result.getRawHash();

                pendingPacks.put(target.getUniqueId(), workingUrl);

                LastPackInfo info = new LastPackInfo(parsedArgs.getUrls(), workingUrl, rawHash);
                lastPlayerPacks.put(target.getUniqueId(), info);
                globalLastPack = info;

                if (hashBytes != null) {
                    target.setResourcePack(workingUrl, hashBytes);
                } else {
                    target.setResourcePack(workingUrl);
                }

                sender.sendMessage("Resourcepack verstuurd naar " + target.getName() + " (" + workingUrl + ")");
            });
        });

        return true;
    }

    private boolean handleListCommand(CommandSender sender) {
        LastPackInfo info = null;
        if (sender instanceof Player) {
            info = lastPlayerPacks.get(((Player) sender).getUniqueId());
        }
        if (info == null) {
            info = globalLastPack;
        }

        if (info == null) {
            sender.sendMessage("Er zijn nog geen resourcepacks toegewezen.");
            return true;
        }

        sender.sendMessage("§e=== Laatst Gebruikte Resourcepack Info ===");
        sender.sendMessage("§7Opgegeven mirrors: §f" + String.join(", ", info.getOriginalUrls()));
        sender.sendMessage("§7Gekozen mirror: §a" + info.getWorkingUrl());
        sender.sendMessage("§7SHA-1 Hash: §f" + (info.getRawHash() != null ? info.getRawHash() : "geen / onbekend"));
        return true;
    }

    private boolean handleTestCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Gebruik: /assignpack test <url1> [url2] ... [sha1|auto]");
            return true;
        }

        ResourcePackUtils.ParsedArgs parsedArgs = ResourcePackUtils.parseUrlsAndHash(args, 1);
        if (parsedArgs.getUrls().isEmpty()) {
            sender.sendMessage("Geen geldige URLs opgegeven om te testen.");
            return true;
        }

        sender.sendMessage("§eBezig met testen van " + parsedArgs.getUrls().size() + " mirror(s)...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (String url : parsedArgs.getUrls()) {
                boolean ok = ResourcePackUtils.testMirrorUrl(url);
                String sha1Info = "";
                if (ok) {
                    String sha1 = ResourcePackUtils.fetchSha1Sidecar(url);
                    if (sha1 != null) {
                        sha1Info = " (sidecar .sha1: " + sha1 + ")";
                    }
                }
                String statusMsg = ok ? "§a[200 OK] §f" + url + sha1Info : "§c[ONBEREIKBAAR] §f" + url;
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(statusMsg));
            }

            ResourcePackUtils.MirrorResult result = ResourcePackUtils.selectMirrorAndFetchHash(
                    parsedArgs.getUrls(),
                    parsedArgs.getHashArg()
            );

            Bukkit.getScheduler().runTask(this, () -> {
                if (result.isSuccess()) {
                    sender.sendMessage("§aGeselecteerde mirror: §f" + result.getWorkingUrl());
                    sender.sendMessage("§aHash: §f" + (result.getRawHash() != null ? result.getRawHash() : "geen / onbekend"));
                } else {
                    sender.sendMessage("§cFout: " + result.getError());
                }
            });
        });

        return true;
    }

    private void applyRestrictions(Player target) {
        pendingPacks.put(target.getUniqueId(), "pending");

        // Save and clear inventory if enabled
        if (config.getBoolean("restrictions.inventory-access")) {
            savedInventories.put(target.getUniqueId(), target.getInventory().getContents());
            target.getInventory().clear();
        }

        // Apply effects based on config
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

    private void removeRestrictions(Player player, boolean isSuccess) {
        if (config.getBoolean("restrictions.inventory-access")) {
            ItemStack[] savedItems = savedInventories.remove(player.getUniqueId());
            if (savedItems != null) {
                player.getInventory().setContents(savedItems);
                getLogger().info("Inventaris van " + player.getName() + " hersteld.");
            }
        }

        // Remove effects and restore movement
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
            boolean hasPendingPack = pendingPacks.containsKey(uuid);
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
            if (pendingPacks.containsKey(uuid) && config.getBoolean("restrictions.damage-protection")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!pendingPacks.containsKey(uuid)) {
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

        // Only remove effects when the pack is successfully loaded or there's an error
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {

            removeRestrictions(player, event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);

            // Add post-download protection if enabled
            int protectionSeconds = config.getInt("post-download-protection-seconds", 0);
            if (protectionSeconds > 0 && event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                protectionEndTimes.put(player.getUniqueId(), System.currentTimeMillis() + (protectionSeconds * 1000L));

                // Schedule removal of protection
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        protectionEndTimes.remove(player.getUniqueId());
                    }
                }.runTaskLater(this, protectionSeconds * 20L);
            }

            // Handle kick messages if needed
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

        // Only remove from pending if the pack is fully loaded or failed
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED ||
            event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            pendingPacks.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (savedInventories.containsKey(player.getUniqueId())) {
            ItemStack[] savedItems = savedInventories.remove(player.getUniqueId());
            player.getInventory().setContents(savedItems);
            getLogger().info("Inventaris van " + player.getName() + " hersteld na disconnectie.");
        }
        pendingPacks.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Remove all potion effects
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        // Restore walk speed
        player.setWalkSpeed(0.2f);

        // Restore inventory if saved
        if (savedInventories.containsKey(player.getUniqueId())) {
            ItemStack[] savedItems = savedInventories.remove(player.getUniqueId());
            if (savedItems != null) {
                player.getInventory().setContents(savedItems);
                getLogger().info("Inventaris van " + player.getName() + " hersteld na herverbinding.");
            }
        }

        // Remove from pendingPacks if present
        pendingPacks.remove(player.getUniqueId());

        // Ensure jump effect is removed
        player.removePotionEffect(PotionEffectType.JUMP);
        player.setVelocity(new Vector(0, 0, 0));
    }
}
