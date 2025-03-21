package ooo.foooooooooooo.velocitydiscord.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import ooo.foooooooooooo.velocitydiscord.VelocityDiscord;
import ooo.foooooooooooo.velocitydiscord.util.StringTemplate;
import ooo.foooooooooooo.velocitydiscord.util.LinkManager;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ooo.foooooooooooo.velocitydiscord.util.LinkManager.generateRandomCode;
import static ooo.foooooooooooo.velocitydiscord.util.LinkManager.storeLinkCode;

public class MessageListener extends ListenerAdapter {
  private static final Pattern WEBHOOK_ID_REGEX = Pattern.compile("^https://discord\\.com/api/webhooks/(\\d+)/.+$");
  private static final Pattern LINK_REGEX =
    Pattern.compile("[^:/?#\\s]+:(?://)?(?:[^?#\\s]+)?(?:\\?[^#\\s]+)?(?:#\\S+)?");
  private final HashMap<String, Discord.Channels> serverChannels;
  private final HashMap<Long, List<String>> channelToServersMap = new HashMap<>();

  private String webhookId;

  private JDA jda;

  public MessageListener(HashMap<String, Discord.Channels> serverChannels) {
    this.serverChannels = serverChannels;
    updateWebhookId();
    onServerChannelsUpdated();
  }

  public void updateWebhookId() {
    final var matcher = WEBHOOK_ID_REGEX.matcher(VelocityDiscord.CONFIG.bot.WEBHOOK_URL);
    this.webhookId = matcher.find() ? matcher.group(1) : null;
    VelocityDiscord.LOGGER.debug("Found webhook id: {}", this.webhookId);
  }

  public void onServerChannelsUpdated() {
    this.channelToServersMap.clear();

    for (var entry : this.serverChannels.entrySet()) {
      this.channelToServersMap
        .computeIfAbsent(entry.getValue().chatChannel.getIdLong(), (k) -> new ArrayList<>())
        .add(entry.getKey());
    }
  }

  @Override
  public void onMessageReceived(@NotNull MessageReceivedEvent event) {
    if (!event.isFromType(ChannelType.TEXT)) {
      VelocityDiscord.LOGGER.trace("Ignoring non-text channel message");
      return;
    }

    if (this.jda == null) {
      this.jda = event.getJDA();
    }

    // Handle commands first, regardless of channel mapping
    String messageContent = event.getMessage().getContentRaw();

    if (messageContent.equalsIgnoreCase("!link")) {
      LinkManager.generateRandomCode()
        .thenCompose(code -> LinkManager.storeLinkCode(event.getAuthor().getId(), code).thenApply(v -> code))
        .whenComplete((code, throwable) -> {
          if (throwable != null) {
            VelocityDiscord.LOGGER.error("Error generating/storing link code for user {}: {}", event.getAuthor().getId(), throwable.getMessage());
            event.getChannel().sendMessage("❌ An error occurred while generating your link code. Please try again later.").queue();
            return;
          }
          event.getAuthor().openPrivateChannel()
            .flatMap(channelPM -> channelPM.sendMessage("Use this code in-game to link: **" + code + "**"))
            .queue(
              success -> {
                event.getChannel().sendMessage("✅ Check your DMs for your linking code!").queue();
                VelocityDiscord.LOGGER.info("Sent link code {} to user {}", code, event.getAuthor().getId());
              },
              failure -> {
                event.getChannel().sendMessage("❌ Failed to send DM. Please ensure your DMs are open.").queue();
                VelocityDiscord.LOGGER.warn("Failed to send DM to user {}: {}", event.getAuthor().getId(), failure.getMessage());
              }
            );
        });
      return;
    } else if (messageContent.equalsIgnoreCase("!verify")) {
      // Handle !verify command (if implemented)
      event.getChannel().sendMessage("✅ Verification request sent! Check the verification channel.").queue();
      return;
    }

    // Existing message forwarding logic
    TextChannel channel = event.getChannel().asTextChannel();
    List<String> targetServerNames = this.channelToServersMap.get(channel.getIdLong());
    if (targetServerNames == null) {
      return;
    }

    VelocityDiscord.LOGGER.trace("Received message from Discord channel {} for servers {}",
      channel.getName(), targetServerNames);

    var messages = new HashMap<String, String>();
    for (var serverName : targetServerNames) {
      messages.put(serverName, serializeMinecraftMessage(event, serverName));
    }

    for (var server : VelocityDiscord.SERVER.getAllServers()) {
      var serverName = server.getServerInfo().getName();
      if (!VelocityDiscord.CONFIG.EXCLUDED_SERVERS_RECEIVE_MESSAGES
        && VelocityDiscord.CONFIG.serverDisabled(serverName)) {
        continue;
      }

      var message = messages.get(serverName);
      if (message == null) continue;

      server.sendMessage(MiniMessage.miniMessage().deserialize(message).asComponent());
    }
  }

  private void sendVerificationMessage(User user) {
    user.openPrivateChannel()
      .flatMap(channel -> channel.sendMessage("✅ Verification request sent! Check the verification channel."))
      .queue();
  }

  private String serializeMinecraftMessage(MessageReceivedEvent event, String server) {
    var serverConfig = VelocityDiscord.CONFIG.getServerConfig(server);
    var serverMinecraftConfig = serverConfig.getMinecraftMessageConfig();

    var author = event.getAuthor();
    if (!serverConfig.getMinecraftMessageConfig().SHOW_BOT_MESSAGES && author.isBot()) {
      VelocityDiscord.LOGGER.debug("ignoring bot message");
      return null;
    }

    if (author.getId().equals(this.jda.getSelfUser().getId()) || (
      Objects.nonNull(this.webhookId) && author.getId().equals(this.webhookId))) {
      VelocityDiscord.LOGGER.debug("ignoring own message");
      return null;
    }

    var message = event.getMessage();
    var guild = event.getGuild();

    var color = Color.white;
    var nickname = author.getName(); // Nickname defaults to username
    var rolePrefix = "";

    var member = guild.getMember(author);
    if (member != null) {
      color = member.getColor();
      if (color == null) {
        color = Color.white;
      }
      nickname = member.getEffectiveName();

      // Get the role prefix
      var highestRole = member
        .getRoles()
        .stream()
        .filter(role -> !serverMinecraftConfig.rolePrefixes.getPrefixForRole(role.getId()).isEmpty())
        .findFirst();

      rolePrefix =
        highestRole.map(role -> serverMinecraftConfig.rolePrefixes.getPrefixForRole(role.getId())).orElse("");
    }

    var hex = "#" + Integer.toHexString(color.getRGB()).substring(2);

    // parse configured message formats
    var discord_chunk = new StringTemplate(serverMinecraftConfig.DISCORD_CHUNK_FORMAT)
      .add("discord_color", serverMinecraftConfig.DISCORD_COLOR)
      .toString();

    var display_name = author.getGlobalName();

    if (display_name == null) {
      display_name = author.getName();
    }

    var username_chunk = new StringTemplate(serverMinecraftConfig.USERNAME_CHUNK_FORMAT)
      .add("role_color", hex)
      .add("username", escapeTags(author.getName()))
      .add("display_name", escapeTags(display_name))
      .add("nickname", escapeTags(nickname))
      .toString();

    var attachment_chunk = serverMinecraftConfig.ATTACHMENT_FORMAT;
    var message_chunk = new StringTemplate(serverMinecraftConfig.MESSAGE_FORMAT)
      .add("discord_chunk", discord_chunk)
      .add("role_prefix", escapeTags(rolePrefix))
      .add("username_chunk", username_chunk)
      .add("message", message.getContentDisplay());

    var attachmentChunks = new ArrayList<String>();

    List<Message.Attachment> attachments = new ArrayList<>();
    if (serverMinecraftConfig.SHOW_ATTACHMENTS) {
      attachments = message.getAttachments();
    }

    for (var attachment : attachments) {
      var chunk = new StringTemplate(attachment_chunk)
        .add("url", attachment.getUrl())
        .add("attachment_color", serverMinecraftConfig.ATTACHMENT_COLOR)
        .toString();

      attachmentChunks.add(chunk);
    }

    var content = message.getContentDisplay();

    // Remove leading whitespace from attachments if there's no content
    if (content.isBlank()) {
      message_chunk = message_chunk.replace(" {attachments}", "{attachments}");
    }

    if (serverMinecraftConfig.LINK_FORMAT.isPresent()) {
      // Replace links with the link format
      content = LINK_REGEX.matcher(content).replaceAll(match -> {
        var url = match.group();
        var replacement = new StringTemplate(serverMinecraftConfig.LINK_FORMAT.get())
          .add("url", url)
          .add("link_color", serverMinecraftConfig.LINK_COLOR)
          .toString();

        return Matcher.quoteReplacement(replacement);
      });
    }

    message_chunk.add("message", content);
    message_chunk.add("attachments", String.join(" ", attachmentChunks));

    return message_chunk.toString();
  }

  private String escapeTags(String input) {
    return input.replace("<", "ᐸ").replace(">", "ᐳ");
  }
}
