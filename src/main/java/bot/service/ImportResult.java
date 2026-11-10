package bot.service;

import bot.model.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ImportResult {
    private final List<ProjectSummary> projects = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> notFoundUsers = new ArrayList<>();
    private boolean hasEmptyRoles = false;

    void addProject(String name, int id, int added, int notFound, Role emptyRole) {
        for (ProjectSummary project : projects) {
            if (project.name.equalsIgnoreCase(name)) {
                addError("Проект \"" + name + "\" — Дублируется имя проекта. Исправьте имя на уникальное.");
                return;
            }
        }
        projects.add(new ProjectSummary(name, id, added, notFound, emptyRole));
    }

    void addError(String error) {
        errors.add(error);
    }

    void addWarning(String warning) {
        errors.add(warning);
    }

    void addEmptyRoles(Set<Role> emptyRoles) {
        if (!emptyRoles.isEmpty()) {
            hasEmptyRoles = true;
        }
    }

    void addNotFound(String userName, String projectName, String closestMatch) {
        if (closestMatch != null) {
            notFoundUsers.add(userName + " (проект: " + projectName + ") — 💡 Возможно, опечатка? Похож на: <b>" + closestMatch + "</b>");
        } else {
            notFoundUsers.add(userName + " (проект: " + projectName + ")");
        }
    }

    public List<ProjectSummary> getProjects() {
        return projects;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getNotFoundUsers() {
        return notFoundUsers;
    }

    // 1. КРИТИЧНО: есть пустые поля (блокирует коммит)
    public boolean hasEmptyFields() {
        return hasEmptyRoles;
    }

    // 2. ПРЕДУПРЕЖДЕНИЕ: есть не найденные юзеры (админ решает)
    public boolean hasNotFoundUsers() {
        return !notFoundUsers.isEmpty();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }


    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Импортировано проектов: ").append(projects.size()).append("\n");
        int totalUsers = projects.stream().mapToInt(p -> p.added).sum();
        sb.append("Назначено пользователей: ").append(totalUsers).append("\n");

        // Критичные ошибки (пустые поля)
        if (hasErrors()) {
            sb.append("\n❌ <b>Критические ошибки (импорт заблокирован):</b>\n");
            for (String s : getErrors()) {
                sb.append(s).append("\n");
            }
        }

        // Предупреждения (не найдены юзеры)
        if (!notFoundUsers.isEmpty()) {
            sb.append("\n⚠️ <b>Предупреждения. Не найдены в БД:</b>\n");
            notFoundUsers.forEach(u -> sb.append("  ").append(u).append("\n"));
        }

        return sb.toString();
    }

    public static class ProjectSummary {
        public final String name;
        public final int id;
        public final int added;
        public final int notFound;
        public final Role emptyRole;

        ProjectSummary(String name, int id, int added, int notFound, Role emptyRole) {
            this.name = name;
            this.id = id;
            this.added = added;
            this.notFound = notFound;
            this.emptyRole = emptyRole;
        }
    }
}
