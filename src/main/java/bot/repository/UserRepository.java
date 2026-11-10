package bot.repository;

import bot.model.Role;
import bot.model.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserRepository {
    private final Connection connection;

    public UserRepository(Connection connection) {
        this.connection = connection;
    }

    public Optional<User> findById(long chatId) {
        String sql = "SELECT * FROM users WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
        return Optional.empty();
    }

    public void save(User user) {
        Optional<User> existing = findById(user.getChatId());
        if (existing.isPresent()) {
            update(user);
        } else {
            insert(user);
        }
    }

    public String getName(long chatId) {
        String sql = "SELECT name FROM users WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
        return "Неизвестный";
    }

    public void updateName(long chatId, String newName) {
        String sql = "UPDATE users SET name=? WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("updateName failed", e);
        }
    }

    public List<User> findByRole(Role role) {
        String column = roleToColumn(role);
        String sql = "SELECT * FROM users WHERE " + column + "=1";
        List<User> users = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByRole failed", e);
        }
        return users;
    }

    public List<Long> findChatIdsByRole(Role role) {
        String column = roleToColumn(role);
        String sql = "SELECT chatId FROM users WHERE " + column + "=1";
        List<Long> chatIds = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                chatIds.add(Long.parseLong(rs.getString("chatId")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findChatIdsByRole failed", e);
        }
        return chatIds;
    }

    public int countByRole(Role role) {
        String column = roleToColumn(role);
        String sql = "SELECT COUNT(*) FROM users WHERE " + column + "=1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("countByRole failed", e);
        }
        return 0;
    }

    public void addRole(long chatId, Role role) {
        String column = roleToColumn(role);
        String sql = "UPDATE users SET " + column + "=1 WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    public void removeRole(long chatId, Role role) {
        String column = roleToColumn(role);
        try {
            // Мягкое снятие роли: сбрасываем флаг роли в 0, сохраняя запись пользователя для истории
            String sql = "UPDATE users SET " + column + "=0 WHERE chatId=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(chatId));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    /**
     * Мягкое удаление пользователя: обнуляет все роли в users, сохраняя запись и имя для истории документов.
     */
    public void deactivateUser(long chatId) {
        String sql = "UPDATE users SET isUser=0, isAdmin=0, isAcceptor=0, isReceiver=0, isRegistrar=0 WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deactivateUser failed", e);
        }
    }

    public String getTextUserList(Role role) {
        String column = roleToColumn(role);
        String sql = "SELECT chatId, name FROM users WHERE " + column + "=1";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append(rs.getString("chatId"))
                  .append(" -- ")
                  .append(rs.getString("name"))
                  .append("\n");
            }
        } catch (SQLException e) {
            throw new RuntimeException("getTextUserList failed", e);
        }
        return sb.length() > 0 ? sb.toString() : "Список пуст";
    }

    private void insert(User user) {
        String sql = "INSERT INTO users (chatId, name, state, lastMessage, mesIdsAndDocIds, showInfo, " +
                "isUser, isAdmin, isAcceptor, isReceiver, isRegistrar) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, user.getChatId());
            ps.setString(2, user.getName());
            ps.setString(3, user.getState());
            ps.setString(4, user.getPreviousMessage());
            ps.setString(5, user.getMesIdsAndDocIds());
            ps.setInt(6, user.getShowInfo() ? 1 : 0);
            ps.setInt(7, user.hasRole(Role.USER) ? 1 : 0);
            ps.setInt(8, user.hasRole(Role.ADMIN) ? 1 : 0);
            ps.setInt(9, user.hasRole(Role.APPROVER) ? 1 : 0);
            ps.setInt(10, user.hasRole(Role.RECEIVER) ? 1 : 0);
            ps.setInt(11, user.hasRole(Role.REGISTRAR) ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    private void update(User user) {
        String sql = "UPDATE users SET name=?, state=?, lastMessage=?, mesIdsAndDocIds=?, showInfo=?, " +
                "isUser=?, isAdmin=?, isAcceptor=?, isReceiver=?, isRegistrar=? WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, user.getName());
            ps.setString(2, user.getState());
            ps.setString(3, user.getPreviousMessage());
            ps.setString(4, user.getMesIdsAndDocIds());
            ps.setInt(5, user.getShowInfo() ? 1 : 0);
            ps.setInt(6, user.hasRole(Role.USER) ? 1 : 0);
            ps.setInt(7, user.hasRole(Role.ADMIN) ? 1 : 0);
            ps.setInt(8, user.hasRole(Role.APPROVER) ? 1 : 0);
            ps.setInt(9, user.hasRole(Role.RECEIVER) ? 1 : 0);
            ps.setInt(10, user.hasRole(Role.REGISTRAR) ? 1 : 0);
            ps.setLong(11, user.getChatId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        long chatId = Long.parseLong(rs.getString("chatId"));
        String name = rs.getString("name");

        User user = new User(chatId, name, Role.USER); // временно, роли ниже перезапишем

        user.setState(rs.getString("state"));
        user.setPreviousMessage(rs.getString("lastMessage"));
        user.setMesIdsAndDocIds(rs.getString("mesIdsAndDocIds"));
        user.setShowInfo(rs.getInt("showInfo") == 1);

        if (rs.getInt("isAdmin") == 1) user.addRole(Role.ADMIN);
        if (rs.getInt("isAcceptor") == 1) user.addRole(Role.APPROVER);
        if (rs.getInt("isReceiver") == 1) user.addRole(Role.RECEIVER);
        if (rs.getInt("isRegistrar") == 1) user.addRole(Role.REGISTRAR);
        if (rs.getInt("isUser") == 0) user.getRoles().remove(Role.USER);

        return user;
    }

    private String roleToColumn(Role role) {
        return switch (role) {
            case USER -> "isUser";
            case ADMIN -> "isAdmin";
            case APPROVER -> "isAcceptor";
            case RECEIVER -> "isReceiver";
            case REGISTRAR -> "isRegistrar";
        };
    }

    public List<User> findAll() {
        String sql = "SELECT * FROM users";
        List<User> users = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAll failed", e);
        }
        return users;
    }
}
