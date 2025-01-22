package ooo.foooooooooooo.velocitydiscord.discord.commands;

import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import ooo.foooooooooooo.velocitydiscord.VelocityDiscord;
import ooo.foooooooooooo.velocitydiscord.util.StringTemplate;

public class ListCommand implements ICommand {
  public ListCommand() {}

  @Override
  public void handle(SlashCommandInteraction interaction) {
    final var servers = VelocityDiscord.SERVER.getAllServers();

    final var sb = new StringBuilder();
    sb.append("```").append(VelocityDiscord.CONFIG.listCommand.CODEBLOCK_LANG).append('\n');

    for (var server : servers) {
      var name = server.getServerInfo().getName();

      if (VelocityDiscord.CONFIG.serverDisabled(name)) {
        continue;
      }

      var players = server.getPlayersConnected();

      var state = VelocityDiscord.getListener().getServerState(server);

      var serverInfo = new StringTemplate(VelocityDiscord.CONFIG.listCommand.SERVER_FORMAT)
        .add("server_name", VelocityDiscord.CONFIG.serverName(name))
        .add("online_players", state.players)
        .add("max_players", state.maxPlayers)
        .toString();

      sb.append(serverInfo).append('\n');

      if (!state.online && VelocityDiscord.CONFIG.listCommand.SERVER_OFFLINE_FORMAT.isPresent()) {
        sb.append(VelocityDiscord.CONFIG.listCommand.SERVER_OFFLINE_FORMAT.get()).append('\n');
      } else if (state.players == 0 && VelocityDiscord.CONFIG.listCommand.NO_PLAYERS_FORMAT.isPresent()) {
        sb.append(VelocityDiscord.CONFIG.listCommand.NO_PLAYERS_FORMAT.get()).append('\n');
      } else {
        for (var player : players) {
          var user = new StringTemplate(VelocityDiscord.CONFIG.listCommand.PLAYER_FORMAT)
            .add("username", player.getUsername())
            .toString();

          sb.append(user).append('\n');
        }
      }

      sb.append('\n');
    }
    sb.append("```");

    interaction.reply(sb.toString()).setEphemeral(VelocityDiscord.CONFIG.listCommand.EPHEMERAL).queue();
  }
}
