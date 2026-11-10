package bot.model;

import bot.repository.ProjectRepository;
import bot.repository.UserRepository;

import java.util.List;

public class Project {
    private final String projectName;
    private final String tableName;
    private final int id;
    private final boolean isArchived;

    private String description;
    private List<Long> users;
    private List<Long> approvers;
    private List<Long> registrars;
    private List<Long> receivers;

    public Project(String projectName, String description, int id, boolean isArchived, List<Long> users, List<Long> approvers, List<Long> registarars, List<Long> receiver) {
        this.projectName = projectName;
        this.description = description;
        this.tableName = ProjectRepository.getTableName(projectName);
        this.id = id;
        this.isArchived = isArchived;
        this.users = users;
        this.approvers = approvers;
        this.registrars = registarars;
        this.receivers = receiver;
    }

    public Project(String projectName, String description, int id, boolean isArchived) {
        this.projectName = projectName;
        this.description = description;
        this.tableName = ProjectRepository.getTableName(projectName);
        this.id = id;
        this.isArchived = isArchived;
    }
    public String getFullInfo(UserRepository userRepo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Проект:</b> ").append(projectName);
        if (isArchived) {
            sb.append(" <i>[В АРХИВЕ]</i>");
        }
        sb.append("\n");

        if (description != null && !description.isBlank()) {
            sb.append("<b>Описание:</b> ").append(description).append("\n");
        }
        sb.append("<b>ID проекта:</b> ").append(id).append("\n\n");

        appendRoleList(sb, "Пользователи", users, userRepo);
        appendRoleList(sb, "Согласующие", approvers, userRepo);
        appendRoleList(sb, "Регистраторы", registrars, userRepo);
        appendRoleList(sb, "Получатели", receivers, userRepo);

        return sb.toString();
    }

    private void appendRoleList(StringBuilder sb, String title, List<Long> chatIds, UserRepository userRepo) {
        if (chatIds == null || chatIds.isEmpty()) return;
        sb.append("• <u>").append(title).append(":</u> ");
        List<String> names = chatIds.stream()
                .map(userRepo::getName)
                .toList();
        sb.append(String.join(", ", names)).append("\n");
    }


    public String getProjectName() {
        return projectName;
    }

    public String getDescription() {
        return description;
    }

    public String getTableName() {
        return tableName;
    }

    public int getId() {
        return id;
    }

    public boolean isArchived() { return isArchived; }

    public List<Long> getUsers() {
        return users;
    }

    public void setUsers(List<Long> users) {
        this.users = users;
    }

    public List<Long> getApprovers() {
        return approvers;
    }

    public void setApprovers(List<Long> approvers) {
        this.approvers = approvers;
    }

    public List<Long> getRegistrars() {
        return registrars;
    }

    public void setRegistrars(List<Long> registrars) {
        this.registrars = registrars;
    }

    public List<Long> getReceivers() {
        return receivers;
    }

    public void setReceivers(List<Long> receivers) {
        this.receivers = receivers;
    }
}
