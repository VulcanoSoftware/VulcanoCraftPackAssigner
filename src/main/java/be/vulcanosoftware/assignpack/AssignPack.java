package be.vulcanosoftware.assignpack;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
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

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.util.Vector;

public class AssignPack extends JavaPlugin implements Listener, CommandExecutor {

    private FileConfiguration config;
    private final HashMap<UUID, String> pendingPacks = new HashMap<>();
    private final HashMap<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final HashMap<UUID, Long> protectionEndTimes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        getCommand("assignpack").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Gebruik: /assignpack <speler> <url>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("Speler niet gevonden.");
            return true;
        }
        String url = args[1];
        pendingPacks.put(target.getUniqueId(), url);
        
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
        if (config.getBoolean("restrictions.crouching")) {
            // No direct effect to apply, handled by PlayerMoveEvent
        }
        
        target.setResourcePack(url);
        sender.sendMessage("Resourcepack verstuurd naar " + target.getName());
        return true;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (pendingPacks.containsKey(player.getUniqueId()) && config.getBoolean("restrictions.inventory-access")) {
                event.setCancelled(true);
                player.closeInventory();
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
            if ((pendingPacks.containsKey(player.getUniqueId()) && config.getBoolean("restrictions.damage-protection")) ||
                (protectionEndTimes.containsKey(player.getUniqueId()) && System.currentTimeMillis() < protectionEndTimes.get(player.getUniqueId()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            if (pendingPacks.containsKey(damager.getUniqueId()) && config.getBoolean("restrictions.damage-protection")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (pendingPacks.containsKey(player.getUniqueId())) {
            // Prevent jumping
            if (config.getBoolean("restrictions.jumping") && event.getTo().getY() > event.getFrom().getY()) {
                event.setCancelled(true);
                player.teleport(event.getFrom()); // Teleport back to prevent upward movement
            }
            // Prevent crouching (sneaking)
            if (config.getBoolean("restrictions.crouching") && player.isSneaking()) {
                event.setCancelled(true);
            }
            // Prevent horizontal movement if movement restriction is enabled
            if (config.getBoolean("restrictions.movement")) {
                if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                    event.setCancelled(true);
                    player.teleport(event.getFrom());
                }
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
            if (config.getBoolean("restrictions.inventory-access")) {
                ItemStack[] savedItems = savedInventories.remove(player.getUniqueId());
                if (savedItems != null) {
                    player.getInventory().setContents(savedItems);
                    getLogger().info("Inventaris van " + player.getName() + " hersteld na resourcepack status: " + event.getStatus().name());
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
