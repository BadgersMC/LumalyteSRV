package ooo.foooooooooooo.velocitydiscord;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ooo.foooooooooooo.velocitydiscord.discord.Discord;
import ooo.foooooooooooo.velocitydiscord.util.LinkManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class VelocityListener {
  private final Discord discord;

  private final Map<String, ServerState> serverState = new HashMap<>();

  private boolean firstHealthCheck = true;

  public VelocityListener(Discord discord) {
    this.discord = discord;
  }

  @Subscribe
  public void onPlayerChat(PlayerChatEvent event) {
    var currentServer = event.getPlayer().getCurrentServer();

    if (currentServer.isEmpty()) {
      return;
    }

    var server = currentServer.get().getServerInfo().getName();

    if (VelocityDiscord.CONFIG.serverDisabled(server)) {
      return;
    }

    var username = event.getPlayer().getUsername();
    var uuid = event.getPlayer().getUniqueId();

    var prefix = getPrefix(uuid);

    this.discord.onPlayerChat(username, uuid.toString(), prefix, server, event.getMessage());
  }

  static class LinkCommand implements SimpleCommand {
    public LinkCommand(VelocityDiscord plugin) {
    }

    @Override
    public void execute(Invocation invocation) {
      VelocityDiscord plugin = VelocityDiscord.getInstance(); // Use the singleton instance
      VelocityDiscord.LOGGER.info("Executing /link command for source: {}", invocation.source());
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();

      if (!(source instanceof Player player)) {
        source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
        return;
      }

      if (args.length != 1) {
        player.sendMessage(Component.text("Usage: /link <code>", NamedTextColor.RED));
        return;
      }

      String code = args[0];
      String uuid = player.getUniqueId().toString();

      LinkManager.validateLinkingCode(uuid, code)
        .thenAccept(result -> {
          player.sendMessage(Component.text(result.message, result.success ? NamedTextColor.GREEN : NamedTextColor.RED));

          if (!result.success) {
            return;
          }

          // Assign the Discord role
          String linkedRoleId = VelocityDiscord.CONFIG.bot.LINKED_ROLE_ID;
          if (linkedRoleId == null || linkedRoleId.isEmpty()) {
            VelocityDiscord.LOGGER.warn("Linked role ID not configured. Skipping role assignment for Discord ID {}", result.discordId);
            return;
          }

          VelocityDiscord.getDiscord().getJda().retrieveUserById(result.discordId).queue(
            user -> {
              // Find the guild (assuming the bot is in the guild where the role exists)
              Guild guild = VelocityDiscord.getDiscord().getJda().getGuilds().stream()
                .filter(g -> g.getRoleById(linkedRoleId) != null)
                .findFirst()
                .orElse(null);

              if (guild == null) {
                VelocityDiscord.LOGGER.error("Could not find a guild with role ID {}", linkedRoleId);
                return;
              }

              Role role = guild.getRoleById(linkedRoleId);
              if (role == null) {
                VelocityDiscord.LOGGER.error("Role with ID {} does not exist in guild {}", linkedRoleId, guild.getName());
                return;
              }

              guild.addRoleToMember(user, role).queue(
                success -> VelocityDiscord.LOGGER.info("Assigned role {} to user {} in guild {}", role.getName(), user.getId(), guild.getName()),
                failure -> VelocityDiscord.LOGGER.error("Failed to assign role {} to user {}: {}", role.getName(), user.getId(), failure.getMessage())
              );
            },
            failure -> VelocityDiscord.LOGGER.error("Failed to retrieve Discord user {}: {}", result.discordId, failure.getMessage())
          );
        })
        .exceptionally(throwable -> {
          VelocityDiscord.LOGGER.error("Error processing /link command for player {}: {}", player.getUsername(), throwable.getMessage());
          player.sendMessage(Component.text("An error occurred while linking your account. Please try again later.", NamedTextColor.RED));
          return null;
        });
    }

    static class UnlinkCommand implements SimpleCommand {
      public UnlinkCommand(VelocityDiscord plugin) {
      }

      @Override
      public void execute(Invocation invocation) {
        VelocityDiscord plugin = VelocityDiscord.getInstance(); // Use the singleton instance
        VelocityDiscord.LOGGER.info("Executing /unlink command for source: {}", invocation.source());
        CommandSource source = invocation.source();

        if (!(source instanceof Player player)) {
          source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
          return;
        }

        String uuid = player.getUniqueId().toString();

        LinkManager.unlinkAccount(uuid)
          .thenAccept(result -> {
            player.sendMessage(Component.text(result.message, result.success ? NamedTextColor.GREEN : NamedTextColor.RED));

            if (!result.success) {
              return;
            }

            // Remove the Discord role
            String linkedRoleId = VelocityDiscord.CONFIG.bot.LINKED_ROLE_ID;
            if (linkedRoleId == null || linkedRoleId.isEmpty()) {
              VelocityDiscord.LOGGER.warn("Linked role ID not configured. Skipping role removal for Discord ID {}", result.discordId);
              return;
            }

            VelocityDiscord.getDiscord().getJda().retrieveUserById(result.discordId).queue(
              user -> {
                // Find the guild (assuming the bot is in the guild where the role exists)
                Guild guild = VelocityDiscord.getDiscord().getJda().getGuilds().stream()
                  .filter(g -> g.getRoleById(linkedRoleId) != null)
                  .findFirst()
                  .orElse(null);

                if (guild == null) {
                  VelocityDiscord.LOGGER.error("Could not find a guild with role ID {}", linkedRoleId);
                  return;
                }

                Role role = guild.getRoleById(linkedRoleId);
                if (role == null) {
                  VelocityDiscord.LOGGER.error("Role with ID {} does not exist in guild {}", linkedRoleId, guild.getName());
                  return;
                }

                guild.removeRoleFromMember(user, role).queue(
                  success -> VelocityDiscord.LOGGER.info("Removed role {} from user {} in guild {}", role.getName(), user.getId(), guild.getName()),
                  failure -> VelocityDiscord.LOGGER.error("Failed to remove role {} from user {}: {}", role.getName(), user.getId(), failure.getMessage())
                );
              },
              failure -> VelocityDiscord.LOGGER.error("Failed to retrieve Discord user {}: {}", result.discordId, failure.getMessage())
            );
          })
          .exceptionally(throwable -> {
            VelocityDiscord.LOGGER.error("Error processing /unlink command for player {}: {}", player.getUsername(), throwable.getMessage());
            player.sendMessage(Component.text("An error occurred while unlinking your account. Please try again later.", NamedTextColor.RED));
            return null;
          });
      }

      @Override
      public List<String> suggest(Invocation invocation) {
        return List.of();
      }

      @Override
      public boolean hasPermission(Invocation invocation) {
        return true; // Allow all players to use the command
      }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();

      // If the user has typed "/link " and is starting to type the code
      if (args.length <= 1) {
        return List.of("<code>"); // Suggest placeholder for the code
      }

      return List.of(); // No suggestions after the code
    }

    @Override
    public boolean hasPermission(SimpleCommand.Invocation invocation) {
      return true; // Allow all players to use the command
    }
  }

  @Subscribe
  public void onConnect(ServerConnectedEvent event) {
    updatePlayerCount();

    var server = event.getServer().getServerInfo().getName();

    if (VelocityDiscord.CONFIG.serverDisabled(server)) {
      return;
    }

    setServerOnline(server);

    var username = event.getPlayer().getUsername();
    var previousServer = event.getPreviousServer();
    var previousName = previousServer.map(s -> s.getServerInfo().getName()).orElse(null);

    var uuid = event.getPlayer().getUniqueId();

    var prefix = getPrefix(uuid);

    // if previousServer is disabled but the current server is not, treat it as a join
    if (previousServer.isPresent() && !VelocityDiscord.CONFIG.serverDisabled(previousName)) {
      this.discord.onServerSwitch(username, uuid.toString(), prefix, server, previousName);
    } else {
      this.discord.onJoin(event.getPlayer(), prefix, server);
    }
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    updatePlayerCount();

    var currentServer = event.getPlayer().getCurrentServer();

    var username = event.getPlayer().getUsername();
    var uuid = event.getPlayer().getUniqueId();
    var prefix = getPrefix(uuid);

    if (currentServer.isEmpty()) {
      this.discord.onDisconnect(username, uuid.toString(), prefix, "");
    } else {
      var name = currentServer.get().getServerInfo().getName();

      if (VelocityDiscord.CONFIG.serverDisabled(name)) {
        return;
      }

      setServerOnline(name);

      this.discord.onLeave(username, uuid.toString(), prefix, name);
    }
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.discord.onProxyInitialize();
    updatePlayerCount();
    checkServerHealth();
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    this.discord.onProxyShutdown();
  }

  // theoretically can get notified of a server going offline by listening to
  // com.velocitypowered.api.event.player.KickedFromServerEvent and then parsing
  // the reason Component to check if its server shutting down message or something
  // but this seems like it would fail to work if literally anything in the message changes
  private void onServerOffline(String server) {
    this.discord.onServerStop(server);
  }

  private void onServerOnline(String server) {
    this.discord.onServerStart(server);
  }

  private void updatePlayerCount() {
    this.discord.updateActivityPlayerAmount(VelocityDiscord.SERVER.getPlayerCount());
  }

  private Optional<String> getPrefix(UUID uuid) {
    var luckPerms = VelocityDiscord.getLuckPerms();
    if (luckPerms == null) return Optional.empty();

    var user = luckPerms.getUserManager().getUser(uuid);
    if (user != null) {
      return Optional.ofNullable(user.getCachedData().getMetaData().getPrefix());
    }

    return Optional.empty();
  }

  /**
   * Ping all servers and update online state
   */
  public void checkServerHealth() {
    var servers = VelocityDiscord.SERVER.getAllServers();

    CompletableFuture
      .allOf(servers
        .parallelStream()
        .map((server) -> server.ping().handle((ping, ex) -> handlePing(server, ping, ex)))
        .toArray(CompletableFuture[]::new))
      .join();

    this.firstHealthCheck = false;
  }

  private CompletableFuture<Void> handlePing(RegisteredServer server, ServerPing ping, Throwable ex) {
    var name = server.getServerInfo().getName();

    if (VelocityDiscord.CONFIG.serverDisabled(name)) {
      return CompletableFuture.completedFuture(null);
    }

    var state = this.serverState.getOrDefault(name, ServerState.empty());

    if (ex != null) {
      if (state.online) {
        if (!this.firstHealthCheck) {
          onServerOffline(name);
        }
        state.online = false;
        this.serverState.put(name, state);
      }

      return CompletableFuture.completedFuture(null);
    }

    if (!state.online && !this.firstHealthCheck) {
      onServerOnline(name);
    }

    var players = 0;
    var maxPlayers = 0;

    if (ping.getPlayers().isPresent()) {
      players = ping.getPlayers().get().getOnline();
      maxPlayers = ping.getPlayers().get().getMax();
    }

    state.online = true;
    state.players = players;
    state.maxPlayers = maxPlayers;

    this.serverState.put(name, state);

    return CompletableFuture.completedFuture(null);
  }

  public ServerState getServerState(RegisteredServer server) {
    var name = server.getServerInfo().getName();
    return this.serverState.getOrDefault(name, ServerState.empty());
  }

  private void setServerOnline(String server) {
    var state = this.serverState.getOrDefault(server, ServerState.empty());

    if (!state.online) {
      onServerOnline(server);
      state.online = true;
      this.serverState.put(server, state);
    }
  }

  public static class ServerState {
    public boolean online;
    public int players;
    public int maxPlayers;

    public ServerState(boolean online, int players, int maxPlayers) {
      this.online = online;
      this.players = players;
      this.maxPlayers = maxPlayers;
    }

    public static ServerState empty() {
      return new ServerState(false, 0, 0);
    }
  }
}
