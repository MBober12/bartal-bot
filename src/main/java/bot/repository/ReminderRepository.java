package bot.repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ReminderRepository {
    private final Connection connection;

    public ReminderRepository(Connection connection) {
        this.connection = connection;
    }

    public List<ReminderEntry> findAll() {
        List<ReminderEntry> entries = new ArrayList<>();
        String sql = "SELECT * FROM reminders";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                entries.add(new ReminderEntry(
                        rs.getInt("remindsPassed"),
                        rs.getString("chatId"),
                        rs.getInt("messageId"),
                        rs.getString("stage"),
                        rs.getInt("docId")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAll failed", e);
        }
        return entries;
    }

    public void insert(int docId, String chatId, int messageId, String stage, int remindsPassed) {
        String sql = "INSERT INTO reminders (remindsPassed, chatId, messageId, stage, docId) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, remindsPassed);
            ps.setString(2, chatId);
            ps.setInt(3, messageId);
            ps.setString(4, stage);
            ps.setInt(5, docId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public void incrementRemindsPassed(String chatId, int messageId) {
        String sql = "UPDATE reminders SET remindsPassed = remindsPassed + 1 WHERE chatId=? AND messageId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setInt(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public void delete(String chatId, int messageId) {
        String sql = "DELETE FROM reminders WHERE chatId=? AND messageId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setInt(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public void deleteByDocId(int docId) {
        String sql = "DELETE FROM reminders WHERE docId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, docId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public void deleteByChatId(long chatId) {
        String sql = "DELETE FROM reminders WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public static class ReminderEntry {
        public final int remindsPassed;
        public final String chatId;
        public final int messageId;
        public final String stage;
        public final int docId;

        public ReminderEntry(int remindsPassed, String chatId, int messageId, String stage, int docId) {
            this.remindsPassed = remindsPassed;
            this.chatId = chatId;
            this.messageId = messageId;
            this.stage = stage;
            this.docId = docId;
        }
    }
}
