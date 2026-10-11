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
    private PackMirrorManager packMirrorManager;

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
        packMirrorManager = new PackMirrorManager();
        if (getCommand("assignpack") != null) {
            getCommand("assignpack").setExecutor(this);
            getCommand("assignpack").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    public PackMirrorManager getPackMirrorManager() {
        return packMirrorManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sendHelp(sender);
            return true;
        }

        // Global standalone commands: /assignpack list or /assignpack test <urls...>
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            return handleGlobalList(sender);
        }
        if (args[0].equalsIgnoreCase("test")) {
            if (args.length < 2) {
                sender.sendMessage("Gebruik: /assignpack test <url1> [url2...]");
                return true;
            }
            return handleTest(sender, Arrays.copyOfRange(args, 1, args.length));
        }

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
        } else if (arg1.equals("test")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> test <url1> [url2...]");
                return true;
            }
            return handleTest(sender, Arrays.copyOfRange(args, 2, args.length));
        } else if (arg1.equals("add")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> add <url1> [url2...] [sha1|auto] [uuid] [required]");
                return true;
            }
            String[] rawPackArgs = Arrays.copyOfRange(args, 2, args.length);
            return handleSendPackMulti(sender, target, rawPackArgs, false);
        } else if (arg1.equals("set")) {
            if (args.length < 3) {
                sender.sendMessage("Gebruik: /assignpack <speler> set <url1> [url2...] [sha1|auto] [uuid] [required]");
                return true;
            }
            String[] rawPackArgs = Arrays.copyOfRange(args, 2, args.length);
            return handleSendPackMulti(sender, target, rawPackArgs, true);
        } else {
            // Legacy / default syntax: /assignpack <speler> <url1> [url2...] [sha1|auto] [required]
            String[] rawPackArgs = Arrays.copyOfRange(args, 1, args.length);
            boolean defaultStacking = config.getBoolean("default-stacking", false);
            boolean replace = !defaultStacking;
            return handleSendPackMulti(sender, target, rawPackArgs, replace);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("Gebruik:");
        sender.sendMessage("/assignpack <speler> <url1> [url2...] [sha1|auto] [required]");
        sender.sendMessage("/assignpack <speler> add <url1> [url2...] [sha1|auto] [uuid] [required]");
        sender.sendMessage("/assignpack <speler> set <url1> [url2...] [sha1|auto] [uuid] [required]");
        sender.sendMessage("/assignpack <speler> remove <uuid|index|url>");
        sender.sendMessage("/assignpack <speler> clear");
        sender.sendMessage("/assignpack <speler> list");
        sender.sendMessage("/assignpack list");
        sender.sendMessage("/assignpack test <url1> [url2...]");
    }

    private boolean handleGlobalList(CommandSender sender) {
        PackMirrorManager.LastUsedInfo lastUsed = packMirrorManager.getLastUsedInfo();
        if (lastUsed == null || lastUsed.getMirrors().isEmpty()) {
            sender.sendMessage("Er zijn nog geen resourcepacks of mirrors verwerkt.");
            return true;
        }

        sender.sendMessage("Laatst gebruikte mirrors (" + lastUsed.getMirrors().size() + "):");
        for (int i = 0; i < lastUsed.getMirrors().size(); i++) {
            String m = lastUsed.getMirrors().get(i);
            if (m.equals(lastUsed.getSelectedMirror())) {
                sender.sendMessage((i + 1) + ". " + m + " [ACTIEF]");
            } else {
                sender.sendMessage((i + 1) + ". " + m);
            }
        }
        sender.sendMessage("Geselecteerde mirror: " + lastUsed.getSelectedMirror());
        sender.sendMessage("SHA-1 Hash: " + (lastUsed.getHash() != null && !lastUsed.getHash().isEmpty() ? lastUsed.getHash() : "Geen hash"));
        return true;
    }

    private boolean handleTest(CommandSender sender, String[] urlArgs) {
        if (urlArgs == null || urlArgs.length == 0) {
            sender.sendMessage("Geef minstens één URL op om te testen.");
            return true;
        }

        List<String> urls = Arrays.asList(urlArgs);
        sender.sendMessage("Bezig met testen van " + urls.size() + " mirror(s)...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PackMirrorManager.MirrorTestSummary summary = packMirrorManager.testMirrors(urls);
            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage("--- Mirror Test Resultaten ---");
                List<PackMirrorManager.MirrorTestItem> items = summary.getResults();
                for (int i = 0; i < items.size(); i++) {
                    PackMirrorManager.MirrorTestItem item = items.get(i);
                    if (item.isSuccess()) {
                        sender.sendMessage((i + 1) + ". " + item.getUrl() + " -> " + item.getStatusCode() + " OK (" + item.getDurationMs() + "ms)");
                    } else if (item.getStatusCode() > 0) {
                        sender.sendMessage((i + 1) + ". " + item.getUrl() + " -> HTTP " + item.getStatusCode() + " (" + item.getDurationMs() + "ms)");
                    } else {
                        sender.sendMessage((i + 1) + ". " + item.getUrl() + " -> " + (item.getError() != null ? item.getError() : "Niet bereikbaar") + " (" + item.getDurationMs() + "ms)");
                    }
                }

                if (summary.getSelectedUrl() != null) {
                    sender.sendMessage("Eerste werkende mirror: " + summary.getSelectedUrl());
                    sender.sendMessage("SHA-1 via .sha1: " + (summary.getFetchedHash() != null ? summary.getFetchedHash() : "Niet gevonden"));
                } else {
                    sender.sendMessage("Geen werkende mirrors gevonden.");
                }
            });
        });

        return true;
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
            String hashStr = (pack.getHash() != null && !pack.getHash().isEmpty()) ? " | Hash: " + pack.getHash() : "";
            sender.sendMessage((i + 1) + ". UUID: " + pack.getUuid() + " | URL: " + pack.getUrl() + hashStr);
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

    private boolean handleSendPackMulti(CommandSender sender, Player target, String[] rawPackArgs, boolean replace) {
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(rawPackArgs);
        if (parsed == null || parsed.getUrls().isEmpty()) {
            sender.sendMessage("Ongeldige invoer. Geef minstens één URL op.");
            return true;
        }

        sender.sendMessage("Bezig met selecteren van mirror en hash voor " + target.getName() + "...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> urls = parsed.getUrls();
            String selectedUrl = packMirrorManager.selectFirstWorkingMirror(urls);
            String finalHash = packMirrorManager.resolveHash(parsed.getHash(), selectedUrl);

            packMirrorManager.recordLastUsed(urls, selectedUrl, finalHash);

            Bukkit.getScheduler().runTask(this, () -> {
                if (!target.isOnline()) {
                    sender.sendMessage("Speler " + target.getName() + " is niet meer online.");
                    return;
                }

                UUID packUuid;
                if (parsed.getUuidStr() != null && !parsed.getUuidStr().isEmpty()) {
                    try {
                        packUuid = UUID.fromString(parsed.getUuidStr());
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage("Ongeldige UUID indeling.");
                        return;
                    }
                } else {
                    packUuid = UUID.nameUUIDFromBytes((target.getUniqueId().toString() + selectedUrl).getBytes());
                }

                try {
                    URI uri = URI.create(selectedUrl);
                    ResourcePackInfo.Builder infoBuilder = ResourcePackInfo.resourcePackInfo()
                            .id(packUuid)
                            .uri(uri);

                    if (finalHash != null && !finalHash.isEmpty()) {
                        infoBuilder.hash(finalHash);
                    }

                    ResourcePackInfo packInfo = infoBuilder.build();
                    ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                            .packs(packInfo)
                            .replace(replace)
                            .required(parsed.isRequired())
                            .build();

                    // Track state
                    UUID playerUuid = target.getUniqueId();
                    pendingPacks.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(packUuid);

                    if (replace) {
                        List<PackEntry> list = new ArrayList<>();
                        list.add(new PackEntry(packUuid, selectedUrl, finalHash));
                        activePacks.put(playerUuid, list);
                    } else {
                        List<PackEntry> list = activePacks.computeIfAbsent(playerUuid, k -> new ArrayList<>());
                        list.removeIf(p -> p.getUuid().equals(packUuid));
                        list.add(new PackEntry(packUuid, selectedUrl, finalHash));
                    }

                    applyRestrictions(target);

                    target.sendResourcePacks(request);
                    String hashMsg = (finalHash != null && !finalHash.isEmpty()) ? " [hash=" + finalHash + "]" : " [geen hash]";
                    sender.sendMessage("Resourcepack verstuurd naar " + target.getName() + " via " + selectedUrl + hashMsg + " (replace=" + replace + ")");
                } catch (Exception e) {
                    sender.sendMessage("Fout bij versturen resourcepack: " + e.getMessage());
                }
            });
        });

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
            if ("list".startsWith(partial)) completions.add("list");
            if ("test".startsWith(partial)) completions.add("test");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("test")) {
                completions.add("https://");
            } else {
                String partial = args[1].toLowerCase();
                List<String> subcommands = Arrays.asList("add", "set", "clear", "remove", "list", "test");
                for (String sub : subcommands) {
                    if (sub.startsWith(partial)) {
                        completions.add(sub);
                    }
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
            } else if (sub.equals("add") || sub.equals("set")) {
                String partial = args[2].toLowerCase();
                if ("auto".startsWith(partial)) completions.add("auto");
                if ("https://".startsWith(partial)) completions.add("https://");
            }
        } else {
            String last = args[args.length - 1].toLowerCase();
            if ("auto".startsWith(last)) completions.add("auto");
            if ("true".startsWith(last)) completions.add("true");
            if ("false".startsWith(last)) completions.add("false");
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
