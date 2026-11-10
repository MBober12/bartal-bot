package bot.repository;

import bot.model.Role;
import bot.model.User;
import bot.service.Columns;
import bot.service.PendingProject;
import bot.model.Project;

import java.sql.*;
import java.util.*;

public class ProjectRepository extends Columns {
    private final Connection connection;
    private final String projectsFilePath;
    private final UserRepository userRepo;

    public ProjectRepository(Connection connection, String projectsFilePath, UserRepository userRepo) {
        this.connection = connection;
        this.projectsFilePath = projectsFilePath;
        this.userRepo = userRepo;
    }

    public String getProjectsFilePath() {
        return projectsFilePath;
    }
    public String getPrintedDatabase(UserRepository userRepo) {
        StringBuilder output = new StringBuilder();
        Role[] roles = {Role.USER, Role.APPROVER, Role.REGISTRAR, Role.RECEIVER};
        List<Project> projects = findAllActive();
        for (Project project : projects) {
            String sql = "SELECT * FROM " + project.getTableName() + ";";
            output.append("------------------------------------").append(project.getProjectName()).append("------------------------------------").append("\n");
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                HashMap<Role, StringBuilder> usersOfProject = new HashMap<>();
                for (Role role : roles) {
                    usersOfProject.put(role, new StringBuilder());
                }
                while (rs.next()) {
                    for (Role role : roles) {
                        if (rs.getInt("is" + role) == 1)
                            usersOfProject.get(role).append(userRepo.getName(Long.parseLong(rs.getString("chatId")))).append(",");
                    }
                }
                for (Role role : roles) {
                   output.append(role).append("s:\n");
                   output.append("|||||").append(usersOfProject.get(role).toString()).append("\n");
                }
            } catch (SQLException e) {
                throw new RuntimeException("findAll failed", e);
            }
        }
        return output.toString();
    }

    public int createProject(String name, String description) {
        String tableName = getTableName(name);
        createProjectTable(tableName); // гарантируем, что таблица пользователей проекта существует

        // 1. Проверяем, есть ли уже проект с такой tableName в базе
        int existingId = resolveProjectId(tableName);

        if (existingId != -1) {
            // Проект уже был: снимаем из архива и обновляем описание/имя
            String updateSql = "UPDATE projects SET description=?, isArchived=0 WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                ps.setString(1, description);
                ps.setInt(2, existingId);
                ps.executeUpdate();
                return existingId; // сохраняем родной старый id!
            } catch (SQLException e) {
                throw new RuntimeException("DB update project failed", e);
            }
        } else {
            // Проекта не было: делаем чистый INSERT
            String insertSql = "INSERT INTO projects (name, tableName, description, isArchived) VALUES (?, ?, ?, 0)";
            try (PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, tableName);
                ps.setString(3, description);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("DB insert project failed", e);
            }
        }
        return -1;
    }


    public static String getTableName(String projectName) {
        return safeTableName("project_" + projectName.toLowerCase().replaceAll("[^a-zа-яё0-9]", "_"));
    }

    public void deleteProject(int projectId) {
        String tableName = resolveTableName(projectId);
        if (tableName != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS \"" + tableName + "\"");
            } catch (SQLException e) {
                throw new RuntimeException("DB operation failed", e); 
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM projects WHERE id=?")) {
            ps.setInt(1, projectId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
    }

    public List<Project> findAllActive() {
        List<Project> list = new ArrayList<>();
        String sql = "SELECT * FROM projects WHERE isArchived=0";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Project(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("id"),
                        false
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAll failed", e);
        }
        return list;
    }

    public List<Project> findAllArchived() {
        List<Project> list = new ArrayList<>();
        String sql = "SELECT * FROM projects WHERE isArchived=1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Project(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("id"),
                        true
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAll failed", e);
        }
        return list;
    }

    public List<Project> findAll() {
        List<Project> list = new ArrayList<>();
        String sql = "SELECT * FROM projects";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Project(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("id"),
                        rs.getInt("isArchived") == 1
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAll failed", e);
        }
        return list;
    }

    // 1. Полная очистка всех проектов (в транзакции)
    public void clearAllProjects() {
        String sqlGetTables = "SELECT tableName FROM projects";
        String sqlDeleteProjects = "DELETE FROM projects";
        String sqlResetSeq = "DELETE FROM sqlite_sequence WHERE name='projects'";

        try {
            connection.setAutoCommit(false);

            // 1. Получаем список таблиц проектов для дропа
            List<String> tableNames = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(sqlGetTables)) {
                while (rs.next()) {
                    String tn = rs.getString("tableName");
                    if (tn != null && !tn.isEmpty()) {
                        tableNames.add(tn);
                    }
                }
            }

            // 2. Дропаем таблицы проектов
            for (String tableName : tableNames) {
                try (Statement st = connection.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS \"" + safeTableName(tableName) + "\"");
                }
            }

            // 3. Чистим главную таблицу проектов
            try (Statement st = connection.createStatement()) {
                st.execute(sqlDeleteProjects);
                st.execute("DELETE FROM sqlite_sequence WHERE name='projects'");
            }

            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) {
                throw new RuntimeException("MethodName failed", ex); 
            }
            throw new RuntimeException("clearAllProjects failed", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) {
                throw new RuntimeException("DB operation failed", e); 
            }
        }
    }

    // 2. Транзакционное сохранение списка проектов (bulk save)
    public void saveProjects(List<PendingProject> projects) {
        try {
            connection.setAutoCommit(false);

            // 1. Чистим всё (без коммита)
            List<String> tableNames = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT tableName FROM projects")) {
                while (rs.next()) {
                    String tn = rs.getString("tableName");
                    if (tn != null && !tn.isEmpty()) tableNames.add(tn);
                }
            }
            try (Statement st = connection.createStatement()) {
                st.execute("UPDATE projects SET isArchived=1");
            }
            // 2. Вставляем новые проекты
            for (PendingProject pp : projects) {
                int projectId = createProject(pp.name, pp.description); // создает запись в projects + таблицу

                for (Long key : pp.users.keySet()) {

                    addUser(projectId, key, pp.users.get(key).isUser,  pp.users.get(key).isApprover, pp.users.get(key).isRegistrar, pp.users.get(key).isReceiver);
                }
            }

            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) {
                throw new RuntimeException("MethodName failed", ex); 
            }
            throw new RuntimeException("saveProjects failed", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) {
                throw new RuntimeException("DB operation failed", e); 
            }
        }
    }


    public String getProjectName(int projectId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM projects WHERE id=?")) {
            ps.setInt(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("name");
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
        return null;
    }

    // === Project user management (by projectId) ===

    public void addUser(int projectId, long chatId, boolean isUser, boolean isApprover, boolean isRegistrar, boolean isReceiver) {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return;
        String sql = "INSERT OR REPLACE INTO \"" + tableName + "\" (chatId, isUser, isApprover, isRegistrar, isReceiver) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ps.setInt(2, isUser ? 1 : 0);
            ps.setInt(3, isApprover ? 1 : 0);
            ps.setInt(4, isRegistrar ? 1 : 0);
            ps.setInt(5, isReceiver ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
    }

    private static String safeTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("Table name is null or empty");
        }
        // Только project_ + кириллица/латиница/цифры/подчёркивание
        if (!tableName.matches("^project_[a-zа-я0-9_]+$")) {
            throw new IllegalArgumentException("Invalid table name format: " + tableName);
        }
        return tableName;
    }

    public void removeUser(int projectId, long chatId) {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return;
        String sql = "DELETE FROM \"" + tableName + "\" WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
    }

    public void removeUserFromAllProjects(long chatId) {
        List<Project> allProjects = findAll();
        for (Project p : allProjects) {
            String tableName = p.getTableName();
            if (tableName == null || tableName.isEmpty()) continue;
            String sql = "DELETE FROM \"" + safeTableName(tableName) + "\" WHERE chatId=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(chatId));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("removeUserFromAllProjects failed for table: " + tableName, e);
            }
        }
    }

    public void removeRoleFromAllProjects(long chatId, Role role) {
        String column = switch (role) {
            case USER -> "isUser";
            case APPROVER -> "isApprover";
            case REGISTRAR -> "isRegistrar";
            case RECEIVER -> "isReceiver";
            default -> null;
        };
        if (column == null) return;

        List<Project> allProjects = findAll();
        for (Project p : allProjects) {
            String tableName = p.getTableName();
            if (tableName == null || tableName.isEmpty()) continue;
            String sql = "UPDATE \"" + safeTableName(tableName) + "\" SET " + column + "=0 WHERE chatId=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(chatId));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("removeRoleFromAllProjects failed for table: " + tableName, e);
            }
        }
    }

    public void setRole(int projectId, long chatId, Role role, boolean value) throws Exception {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return;
        String column = switch (role) {
            case USER -> "isUser";
            case APPROVER -> "isApprover";
            case REGISTRAR -> "isRegistrar";
            case RECEIVER -> "isReceiver";
            default -> null;
        };
        if (column == null) return;
        String sql = "UPDATE \"" + tableName + "\" SET " + column + "=? WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, value ? 1 : 0);
            ps.setString(2, String.valueOf(chatId));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
    }

    // === Queries (by projectId) ===

    public List<User> getUsersByRoleAndProjectId(int projectId, Role role) {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return List.of();
        String column = switch (role) {
            case USER -> "isUser";
            case APPROVER -> "isApprover";
            case REGISTRAR -> "isRegistrar";
            case RECEIVER -> "isReceiver";
            default -> null;
        };
        if (column == null) return List.of();

        List<User> list = new ArrayList<>();
        String sql = "SELECT * FROM \"" + tableName + "\" WHERE " + column + "=1";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
        return list;
    }
    public List<Long> getUsersByRoleAndTableName(String tableName, Role role) {
        String safeTableName = safeTableName(tableName);
        if (safeTableName == null) return List.of();
        String column = switch (role) {
            case USER -> "isUser";
            case APPROVER -> "isApprover";
            case REGISTRAR -> "isRegistrar";
            case RECEIVER -> "isReceiver";
            default -> null;
        };
        if (column == null) return List.of();

        List<Long> list = new ArrayList<>();
        String sql = "SELECT * FROM \"" + safeTableName + "\" WHERE " + column + "=1";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(Long.valueOf(rs.getString("chatId")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
        return list;
    }

    public Optional<User> findUser(int projectId, long chatId) {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return Optional.empty();
        String sql = "SELECT * FROM \"" + tableName + "\" WHERE chatId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapRow(rs));
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e); 
        }
        return Optional.empty();
    }

    public List<User> getUsersForProject(int projectId) {
        String tableName = resolveTableName(projectId);
        if (tableName == null) return List.of();
        String sql = "SELECT * FROM \"" + tableName + "\"";
        List<User> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
        return list;
    }
    public List<User> getUsersForProject(String tableName) {
        String safeTableName = safeTableName(tableName);
        if (safeTableName == null) return List.of();
        String sql = "SELECT * FROM \"" + safeTableName + "\"";
        List<User> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
        return list;
    }

    public List<Project> getProjectsForUser(User user) {
        List<Project> projects = findAllActive();
        List<Project> result = new ArrayList<>();
        for (Project p : projects) {
            Optional<User> userOfProject = findUser(p.getId(), user.getChatId());
            if (userOfProject.isPresent()) {
                result.add(p);
            }
        }
        return result;
    }

    public List<Project> getProjectsForUserAndRole(User user, Role role) {
        List<Project> projects = findAllActive();
        List<Project> result = new ArrayList<>();
        for (Project p : projects) {
            Optional<User> userOfProjectOpt = findUser(p.getId(), user.getChatId());
            if (userOfProjectOpt.isPresent()) {
                User userOfProject = userOfProjectOpt.get();
                if (userOfProject.hasRole(role)) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    // === Internal ===

    public String resolveTableName(int projectId) {
        if (projectId == -1) {
            return "null_table";
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT tableName FROM projects WHERE id=?")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String rawName = rs.getString("tableName");
                    if (rawName == null || rawName.isEmpty()) {
                        throw new IllegalStateException("Table name is null/empty for projectId=" + projectId);
                    }
                    return safeTableName(rawName); // валидация + возврат
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Failed to resolve table name for projectId=" + projectId, e);
        }
        throw new IllegalStateException("Project not found: id=" + projectId);
    }

    public int resolveProjectId(String tableName) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM projects WHERE tableName=?")) {
            ps.setString(1, safeTableName(tableName));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("id");
                    if (id == 0) {
                        throw new IllegalStateException("Id is null/empty for tableName=" + tableName);
                    }
                    return id; // валидация + возврат
                } else {
                    return -1;
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Failed to resolve id for tableName=" + tableName, e);
        }
    }

    public String getDescription(String tableName) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM projects WHERE tableName=?")) {
            ps.setString(1, safeTableName(tableName));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String description = rs.getString(4);
                    if (description.isEmpty()) {
                        throw new IllegalStateException("Id is null/empty for tableName=" + tableName);
                    }
                    return description; // валидация + возврат
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Failed to resolve id for tableName=" + tableName, e);
        }
        throw new IllegalStateException("Project not found: tableName=" + tableName);
    }

    public boolean getIsArchived(String tableName) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT isArchived FROM projects WHERE tableName=?")) {
            ps.setString(1, safeTableName(tableName));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("isArchived") == 1;// валидация + возврат
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Failed to resolve id for tableName=" + tableName, e);
        }
        throw new IllegalStateException("Project not found: tableName=" + tableName);
    }

    public Project getProject(String tableName) {
        String safeTableName = safeTableName(tableName);
        int projectId = resolveProjectId(safeTableName);
        if (projectId == -1) {
            // Проекта вообще нет даже в архиве БД
            return new Project(tableName, "Неизвестный проект", -1, true,
                    List.of(), List.of(), List.of(), List.of());
        }
        return new Project(
                getProjectName(resolveProjectId(safeTableName)),
                getDescription(safeTableName),
                resolveProjectId(safeTableName),
                getIsArchived(safeTableName),
                getUsersByRoleAndTableName(safeTableName, Role.USER),
                getUsersByRoleAndTableName(safeTableName, Role.APPROVER),
                getUsersByRoleAndTableName(safeTableName, Role.REGISTRAR),
                getUsersByRoleAndTableName(safeTableName, Role.RECEIVER)
        );
    }


    private void createProjectTable(String tableName) {
        String safe = safeTableName(tableName);
        String sql = "CREATE TABLE IF NOT EXISTS \"" + safe + "\" (" +
                "chatId TEXT PRIMARY KEY, " +
                "isUser INTEGER NOT NULL DEFAULT 0, " +
                "isApprover INTEGER NOT NULL DEFAULT 0, " +
                "isRegistrar INTEGER NOT NULL DEFAULT 0, " +
                "isReceiver INTEGER NOT NULL DEFAULT 0)";
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("DB operation failed", e);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        long chatId = Long.parseLong(rs.getString("chatId"));
        String name = userRepo.getName(chatId);
        User user = new User(chatId, name);

        if (rs.getInt("isUser") == 1) user.addRole(Role.USER);
        if (rs.getInt("isApprover") == 1) user.addRole(Role.APPROVER);
        if (rs.getInt("isRegistrar") == 1) user.addRole(Role.REGISTRAR);
        if (rs.getInt("isReceiver") == 1) user.addRole(Role.RECEIVER);

        return user;
    }
}
