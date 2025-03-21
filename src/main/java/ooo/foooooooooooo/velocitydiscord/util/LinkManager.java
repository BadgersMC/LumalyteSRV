package ooo.foooooooooooo.velocitydiscord.util;

import ooo.foooooooooooo.velocitydiscord.VelocityDiscord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class LinkManager {
  private static final int CODE_EXPIRATION_SECONDS = 900; // 15 minutes

  /**
   * Generates a random 6-digit code that isn’t already in use.
   *
   * @return A CompletableFuture containing the unique 6-digit code.
   */
  public static CompletableFuture<String> generateRandomCode() {
    return CompletableFuture.supplyAsync(() -> {
      Random random = new Random();
      String code;
      boolean isUnique;
      int attempts = 0;
      final int maxAttempts = 10;

      do {
        if (attempts++ >= maxAttempts) {
          throw new IllegalStateException("Failed to generate a unique code after " + maxAttempts +
            " attempts. Fun fact for math nerds: this exception has a 1 in 10^29 chance of occurring " +
            "if we have 1000 users. We have 900,000 potential link codes. If, somehow, miraculously, " +
            "this code fails 10 times in a row to generate a unique 6-digit code, the user should be " +
            "given a free rank and advised to play the lottery. Otherwise, this code is broken.");
        }
        int codeNum = 100000 + random.nextInt(900000); // Range: 100000 to 999999
        code = String.valueOf(codeNum);
        isUnique = true;

        try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
          PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM PendingLinks WHERE code = ?"
          );
          ps.setString(1, code);
          ResultSet rs = ps.executeQuery();
          if (rs.next() && rs.getInt(1) > 0) {
            isUnique = false;
          }
        } catch (SQLException e) {
          VelocityDiscord.LOGGER.error("Failed to check code uniqueness for {}: {}", code, e.getMessage());
          throw new RuntimeException("Database error checking code uniqueness", e);
        }
      } while (!isUnique);

      return code;
    });
  }

  /**
   * Stores a pending link code in the database associated with a Discord ID.
   *
   * @param discordId The Discord user ID.
   * @param code      The generated link code.
   */
  public static CompletableFuture<Void> storeLinkCode(String discordId, String code) {
    return CompletableFuture.runAsync(() -> {
      try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
        PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO PendingLinks (discord_id, code, created_at) VALUES (?, ?, NOW()) " +
            "ON DUPLICATE KEY UPDATE code = VALUES(code), created_at = NOW()"
        );
        ps.setString(1, discordId);
        ps.setString(2, code);
        ps.executeUpdate();
        VelocityDiscord.LOGGER.debug("Stored link code {} for Discord ID {}", code, discordId);
      } catch (SQLException e) {
        VelocityDiscord.LOGGER.error("Failed to store link code for Discord ID {}: {}", discordId, e.getMessage());
        throw new RuntimeException("Database error storing link code", e);
      }
    });
  }

  /**
   * Validates a link code and links the Minecraft UUID to the Discord ID if valid.
   *
   * @param uuid The Minecraft player's UUID.
   * @param code The code entered by the player.
   * @return A CompletableFuture containing a LinkResult with the success status, Discord ID, and message.
   */
  public static CompletableFuture<LinkResult> validateLinkingCode(String uuid, String code) {
    return CompletableFuture.supplyAsync(() -> {
      try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
        conn.setAutoCommit(false); // Start transaction
        try {
          // Check if the UUID is already linked
          PreparedStatement checkLinkedPs = conn.prepareStatement(
            "SELECT discord_id FROM LinkedAccounts WHERE uuid = ?"
          );
          checkLinkedPs.setString(1, uuid);
          ResultSet linkedRs = checkLinkedPs.executeQuery();
          if (linkedRs.next()) {
            conn.rollback();
            return new LinkResult(false, null, "Your account is already linked to a Discord account.");
          }

          // Check if the code exists, get the Discord ID, and check for expiration
          PreparedStatement ps = conn.prepareStatement(
            "SELECT discord_id FROM PendingLinks " +
              "WHERE code = ? AND TIMESTAMPDIFF(SECOND, created_at, NOW()) <= ? LIMIT 1"
          );
          ps.setString(1, code);
          ps.setInt(2, CODE_EXPIRATION_SECONDS);
          ResultSet rs = ps.executeQuery();

          if (rs.next()) {
            String discordId = rs.getString("discord_id");

            // Link the accounts in LinkedAccounts
            PreparedStatement updatePs = conn.prepareStatement(
              "INSERT INTO LinkedAccounts (uuid, discord_id) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE discord_id = VALUES(discord_id)"
            );
            updatePs.setString(1, uuid);
            updatePs.setString(2, discordId);
            updatePs.executeUpdate();

            // Remove the used code from PendingLinks
            PreparedStatement deletePs = conn.prepareStatement(
              "DELETE FROM PendingLinks WHERE code = ?"
            );
            deletePs.setString(1, code);
            deletePs.executeUpdate();

            conn.commit(); // Commit transaction
            VelocityDiscord.LOGGER.info("Linked UUID {} to Discord ID {}", uuid, discordId);
            return new LinkResult(true, discordId, "Successfully linked your account!");
          } else {
            // Check if the code exists but is expired
            PreparedStatement checkPs = conn.prepareStatement(
              "SELECT COUNT(*) FROM PendingLinks WHERE code = ?"
            );
            checkPs.setString(1, code);
            ResultSet checkRs = checkPs.executeQuery();
            if (checkRs.next() && checkRs.getInt(1) > 0) {
              VelocityDiscord.LOGGER.debug("Link code {} has expired for UUID {}", code, uuid);
              // Delete the expired code
              PreparedStatement deletePs = conn.prepareStatement(
                "DELETE FROM PendingLinks WHERE code = ?"
              );
              deletePs.setString(1, code);
              deletePs.executeUpdate();
              conn.commit();
              return new LinkResult(false, null, "This code has expired. Please run !link in Discord to get a new code.");
            }
            conn.rollback();
            return new LinkResult(false, null, "Invalid code. Please run !link in Discord to get a new code.");
          }
        } catch (SQLException e) {
          conn.rollback();
          VelocityDiscord.LOGGER.error("Failed to validate link code {} for UUID {}: {}", code, uuid, e.getMessage());
          throw new RuntimeException("Database error validating link code", e);
        } finally {
          conn.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new RuntimeException("Database connection error", e);
      }
    });
  }

  /**
   * Checks if a Minecraft UUID is already linked to a Discord ID.
   *
   * @param uuid The Minecraft player's UUID.
   * @return A CompletableFuture containing true if the UUID is already linked, false otherwise.
   */
  public static CompletableFuture<Boolean> isAlreadyLinked(String uuid) {
    return CompletableFuture.supplyAsync(() -> {
      try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
        PreparedStatement ps = conn.prepareStatement(
          "SELECT COUNT(*) FROM LinkedAccounts WHERE uuid = ?"
        );
        ps.setString(1, uuid);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          return rs.getInt(1) > 0;
        }
        return false;
      } catch (SQLException e) {
        VelocityDiscord.LOGGER.error("Failed to check if UUID {} is linked: {}", uuid, e.getMessage());
        throw new RuntimeException("Database error checking linked status", e);
      }
    });
  }

  /**
   * Marks a Discord user as verified in the database.
   *
   * @param discordId The Discord user ID to mark as verified.
   * @return A CompletableFuture that completes when the user is marked as verified.
   */
  public static CompletableFuture<Void> markUserAsVerified(String discordId) {
    return CompletableFuture.runAsync(() -> {
      try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
        PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO VerifiedUsers (discord_id, verified) VALUES (?, ?) ON DUPLICATE KEY UPDATE verified = 1"
        );
        ps.setString(1, discordId);
        ps.setInt(2, 1);
        ps.executeUpdate();
        VelocityDiscord.LOGGER.info("Marked Discord user {} as verified", discordId);
      } catch (SQLException e) {
        VelocityDiscord.LOGGER.error("Failed to mark Discord user {} as verified: {}", discordId, e.getMessage());
        throw new RuntimeException("Database error marking user as verified", e);
      }
    });
  }
  /**
   * Unlinks a Minecraft UUID from a Discord ID.
   *
   * @param uuid The Minecraft player's UUID.
   * @return A CompletableFuture containing an UnlinkResult with the success status, Discord ID, and message.
   */
  public static CompletableFuture<UnlinkResult> unlinkAccount(String uuid) {
    return CompletableFuture.supplyAsync(() -> {
      try (Connection conn = VelocityDiscord.getDatabaseManager().getConnection()) {
        conn.setAutoCommit(false); // Start transaction
        try {
          // Check if the UUID is linked and get the Discord ID
          PreparedStatement selectPs = conn.prepareStatement(
            "SELECT discord_id FROM LinkedAccounts WHERE uuid = ?"
          );
          selectPs.setString(1, uuid);
          ResultSet rs = selectPs.executeQuery();

          if (!rs.next()) {
            conn.rollback();
            return new UnlinkResult(false, null, "Your account is not linked to a Discord account.");
          }

          String discordId = rs.getString("discord_id");

          // Remove the link from LinkedAccounts
          PreparedStatement deleteLinkPs = conn.prepareStatement(
            "DELETE FROM LinkedAccounts WHERE uuid = ?"
          );
          deleteLinkPs.setString(1, uuid);
          deleteLinkPs.executeUpdate();

          // Remove from VerifiedUsers (optional, depending on your requirements)
          PreparedStatement deleteVerifiedPs = conn.prepareStatement(
            "DELETE FROM VerifiedUsers WHERE discord_id = ?"
          );
          deleteVerifiedPs.setString(1, discordId);
          deleteVerifiedPs.executeUpdate();

          conn.commit(); // Commit transaction
          VelocityDiscord.LOGGER.info("Unlinked UUID {} from Discord ID {}", uuid, discordId);
          return new UnlinkResult(true, discordId, "Successfully unlinked your account!");
        } catch (SQLException e) {
          conn.rollback();
          VelocityDiscord.LOGGER.error("Failed to unlink UUID {}: {}", uuid, e.getMessage());
          throw new RuntimeException("Database error unlinking account", e);
        } finally {
          conn.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new RuntimeException("Database connection error", e);
      }
    });
  }

  /**
   * Result of a linking operation.
   */
  public static class LinkResult {
    public final boolean success;
    public final String discordId;
    public final String message;

    public LinkResult(boolean success, String discordId, String message) {
      this.success = success;
      this.discordId = discordId;
      this.message = message;
    }
  }

  /**
   * Result of an unlinking operation.
   */
  public static class UnlinkResult {
    public final boolean success;
    public final String discordId;
    public final String message;

    public UnlinkResult(boolean success, String discordId, String message) {
      this.success = success;
      this.discordId = discordId;
      this.message = message;
    }
  }
}