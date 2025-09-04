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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.UUID;

public class AssignPack extends JavaPlugin implements Listener, CommandExecutor {

    private FileConfiguration config;
    private final HashMap<UUID, String> pendingPacks = new HashMap<>();

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
        target.setResourcePack(url);
        sender.sendMessage("Resourcepack verstuurd naar " + target.getName());
        return true;
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
            if (config.getBoolean("kick-on-decline")) {
                player.kickPlayer(config.getString("kick-message-decline"));
            }
        } else if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            if (config.getBoolean("kick-on-failure")) {
                player.kickPlayer(config.getString("kick-message-failure"));
            }
        }
        pendingPacks.remove(player.getUniqueId());
    }
}
